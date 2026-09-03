package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository.instant;

@Repository
public class AiTaskRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AiQueueProperties properties;
    private final B2StorageProperties b2;

    public AiTaskRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            AiQueueProperties properties, B2StorageProperties b2) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(5);
        this.properties = properties;
        this.b2 = b2;
    }

    public List<AiClaim> claim(AiSettings settings, int size) {
        AiQueueProperties.validateSize(size);
        if (!settings.enabled()) return List.of();
        return transactions.execute(tx -> {
            jdbc.getJdbcTemplate().execute("SET LOCAL statement_timeout='5s'");
            recoverExpired();
            Instant expires = databaseNow().plus(properties.leaseDuration());
            List<AiClaim> claimed = new ArrayList<>();
            for (AiRule rule : settings.rules()) {
                if (!rule.enabled() || claimed.size() == size) continue;
                MapSqlParameterSource parameters = new MapSqlParameterSource("limit", size - claimed.size())
                        .addValue("retentionSeconds", b2.metadataRetention().toSeconds());
                StringBuilder sql = new StringBuilder("""
                        SELECT ai.image_id, ia.payload_raw
                        FROM ai_review_task ai JOIN image_asset ia ON ia.id=ai.image_id
                        WHERE ai.status='PENDING' AND ai.game_code='bj_single_deck_ags'
                          AND (ai.retry_after IS NULL OR ai.retry_after <= CURRENT_TIMESTAMP)
                        """);
                if (b2.enabled()) sql.append("""
                        AND (ia.file_available OR (NULLIF(BTRIM(ia.cloud_object_key),'') IS NOT NULL
                          AND ia.cloud_uploaded_at > CURRENT_TIMESTAMP - (:retentionSeconds * INTERVAL '1 second')))
                        """);
                else sql.append(" AND ia.file_available=TRUE ");
                condition(sql, parameters, "ai.file_created_at >= :createdFrom", "createdFrom", timestamp(rule.createdFrom()));
                condition(sql, parameters, "ai.file_created_at < :createdTo", "createdTo", timestamp(rule.createdTo()));
                condition(sql, parameters, "ai.token_id = :tokenId", "tokenId", rule.tokenId());
                condition(sql, parameters, "ai.session_id = :sessionId", "sessionId", rule.sessionId());
                condition(sql, parameters, "ai.is_notification = :notification", "notification", rule.notification());
                condition(sql, parameters, "ai.has_user_hand = :hasUserHand", "hasUserHand", rule.hasUserHand());
                sql.append(" ORDER BY ai.file_created_at, ai.image_id LIMIT :limit FOR UPDATE OF ai SKIP LOCKED");
                List<AiClaim> candidates = jdbc.query(sql.toString(), parameters, (rs, row) ->
                        new AiClaim(rs.getString("image_id"), UUID.randomUUID(), expires, rs.getString("payload_raw")));
                for (AiClaim candidate : candidates) {
                    jdbc.update("""
                            UPDATE ai_review_task SET status='PROCESSING', claim_id=:claim,
                              lease_expires_at=:expires, attempt_count=attempt_count+1,
                              issued_rule_id=:rule, expected=NULL, game='SINGLE_DECK', retry_after=NULL,
                              last_error_code=NULL, last_error_message=NULL, last_error_at=NULL
                            WHERE image_id=:id
                            """, key(candidate).addValue("expires", timestamp(expires)).addValue("rule", rule.id()));
                    claimed.add(candidate);
                }
            }
            return List.copyOf(claimed);
        });
    }

    private void recoverExpired() {
        jdbc.update("""
                UPDATE ai_review_task SET status='PENDING', claim_id=NULL, lease_expires_at=NULL,
                  retry_after=NULL, last_error_code='LEASE_EXPIRED',
                  last_error_message='Previous AI claim expired without an accepted result', last_error_at=clock_timestamp()
                WHERE image_id IN (
                  SELECT image_id FROM ai_review_task
                  WHERE status='PROCESSING' AND lease_expires_at <= CURRENT_TIMESTAMP
                  ORDER BY lease_expires_at, image_id LIMIT 100 FOR UPDATE SKIP LOCKED
                )
                """, new MapSqlParameterSource());
    }

    public void complete(String imageId, AiResult result) {
        transactions.executeWithoutResult(tx -> {
            var rows = jdbc.query("SELECT * FROM ai_review_task WHERE image_id=:id FOR UPDATE",
                    new MapSqlParameterSource("id", imageId), (rs, row) -> new StoredResult(
                            rs.getString("status"), rs.getObject("claim_id", UUID.class), instant(rs, "lease_expires_at"),
                            rs.getObject("valid", Boolean.class), rs.getString("verdict"), rs.getObject("certainty", Integer.class),
                            rs.getObject("confidence", Integer.class), rs.getString("message")));
            if (rows.isEmpty()) throw new AiQueueException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "AI task does not exist");
            StoredResult stored = rows.getFirst();
            if (!result.claimId().equals(stored.claimId)) throw stale();
            if (stored.status.equals("COMPLETED")) {
                if (!result.equals(new AiResult(stored.claimId, stored.valid, stored.verdict, stored.certainty, stored.confidence, stored.message))) {
                    throw new AiQueueException(HttpStatus.CONFLICT, "RESULT_CONFLICT", "This claim already has a different result");
                }
                return;
            }
            // Read actual database time AFTER acquiring the row lock, not transaction start time.
            Instant now = databaseNow();
            if (!stored.status.equals("PROCESSING") || stored.expires == null || !stored.expires.isAfter(now)) throw stale();
            jdbc.update("""
                    UPDATE ai_review_task SET status='COMPLETED', valid=:valid, verdict=:verdict,
                      certainty=:certainty, confidence=:confidence, message=:message, checked_at=:now,
                      retry_after=NULL, last_error_code=NULL, last_error_message=NULL, last_error_at=NULL
                    WHERE image_id=:id
                    """, new MapSqlParameterSource("id", imageId).addValue("valid", result.valid())
                    .addValue("verdict", result.verdict()).addValue("certainty", result.certainty())
                    .addValue("confidence", result.confidence()).addValue("message", result.message()).addValue("now", timestamp(now)));
        });
    }

    public boolean savePrepared(AiClaim claim, String expected) {
        return jdbc.update("""
                UPDATE ai_review_task SET expected=:expected
                WHERE image_id=:id AND claim_id=:claim AND status='PROCESSING' AND lease_expires_at > clock_timestamp()
                """, key(claim).addValue("expected", expected)) == 1;
    }

    public void preparationFailed(AiClaim claim, boolean permanent, String code) {
        jdbc.update("""
                UPDATE ai_review_task SET status=:status, claim_id=NULL, lease_expires_at=NULL,
                  retry_after=CASE WHEN :permanent THEN NULL ELSE clock_timestamp()+INTERVAL '30 seconds' END,
                  last_error_code=:code, last_error_message=:code, last_error_at=clock_timestamp()
                WHERE image_id=:id AND claim_id=:claim AND status='PROCESSING' AND lease_expires_at > clock_timestamp()
                """, key(claim).addValue("status", permanent ? "FAILED" : "PENDING")
                .addValue("permanent", permanent).addValue("code", code));
    }

    public AiResultDetails details(String imageId) {
        return jdbc.query("SELECT * FROM ai_review_task WHERE image_id=:id", new MapSqlParameterSource("id", imageId),
                (rs, row) -> mapDetails(rs)).stream().findFirst().orElse(null);
    }

    private static AiResultDetails mapDetails(ResultSet rs) throws SQLException {
        return new AiResultDetails(rs.getString("status"), rs.getObject("valid", Boolean.class), rs.getString("verdict"),
                rs.getObject("certainty", Integer.class), rs.getObject("confidence", Integer.class), rs.getString("message"),
                instant(rs, "checked_at"), rs.getInt("attempt_count"), rs.getString("last_error_code"),
                rs.getString("last_error_message"), instant(rs, "last_error_at"));
    }

    public Instant databaseNow() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }
    private static MapSqlParameterSource key(AiClaim claim) {
        return new MapSqlParameterSource("id", claim.imageId()).addValue("claim", claim.claimId());
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static void condition(StringBuilder sql, MapSqlParameterSource p, String predicate, String name, Object value) {
        if (value != null) { sql.append(" AND ").append(predicate); p.addValue(name, value); }
    }
    private static AiQueueException stale() {
        return new AiQueueException(HttpStatus.CONFLICT, "STALE_CLAIM", "Claim expired or was replaced");
    }
    private record StoredResult(String status, UUID claimId, Instant expires, Boolean valid, String verdict,
                                Integer certainty, Integer confidence, String message) {}
}
