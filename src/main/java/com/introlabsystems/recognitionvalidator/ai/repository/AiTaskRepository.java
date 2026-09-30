package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import com.introlabsystems.recognitionvalidator.ai.mapper.AiJdbcMapping;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.introlabsystems.recognitionvalidator.ai.mapper.AiJdbcMapping.instant;
import static com.introlabsystems.recognitionvalidator.ai.mapper.AiJdbcMapping.timestamp;

@Repository
@Slf4j
public class AiTaskRepository {
    private static final String EXPIRED_SELECTION = """
            SELECT image_id FROM ai_review_task
            WHERE status='PROCESSING' AND lease_expires_at <= :now
            ORDER BY lease_expires_at, image_id LIMIT 100
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AiQueueProperties properties;
    private final B2StorageProperties b2;
    private final DailyStatisticsRepository dailyStatistics;
    private final ReviewDisagreementRepository disagreements;
    private final AiRuleActivityRepository activity;

    public AiTaskRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            AiQueueProperties properties, B2StorageProperties b2,
                            DailyStatisticsRepository dailyStatistics,
                            ReviewDisagreementRepository disagreements, AiRuleActivityRepository activity) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(5);
        this.properties = properties;
        this.b2 = b2;
        this.dailyStatistics = dailyStatistics;
        this.disagreements = disagreements;
        this.activity = activity;
    }

    public List<AiClaim> claim(AiSettings settings, int size) {
        AiQueueProperties.validateSize(size);
        if (!settings.enabled()) return List.of();
        return transactions.execute(tx -> {
            jdbc.getJdbcTemplate().execute("SET LOCAL statement_timeout='5s'");
            recoverExpired();
            Instant claimNow = databaseNow();
            Instant expires = claimNow.plus(properties.leaseDuration());
            List<AiClaim> claimed = new ArrayList<>();
            var issuedCounts = new java.util.HashMap<UUID, Integer>();
            for (AiRule rule : settings.rules()) {
                if (claimed.size() == size) break;
                if (!rule.enabled()) continue;
                MapSqlParameterSource parameters = new MapSqlParameterSource("limit", size - claimed.size())
                        .addValue("now", timestamp(claimNow));
                StringBuilder sql = eligiblePendingSql(parameters, "ai.image_id, ia.file_name, ai.game_code", claimNow);
                appendRuleConditions(sql, parameters, rule, "");
                sql.append(" ORDER BY ai.file_created_at, ai.image_id LIMIT :limit FOR UPDATE OF ai SKIP LOCKED");
                List<AiClaim> candidates = jdbc.query(sql.toString(), parameters, (rs, row) ->
                        new AiClaim(rs.getString("image_id"), UUID.randomUUID(), rs.getString("file_name"),
                                rs.getString("game_code"), expires));
                log.debug("AI rule candidates selected: revision={}, ruleId={}, priority={}, selected={}, remainingSlots={}",
                        settings.revision(), rule.id(), rule.priority(), candidates.size(), size - claimed.size());
                for (AiClaim candidate : candidates) {
                    jdbc.update("""
                            UPDATE ai_review_task SET status='PROCESSING', claim_id=:claim,
                              lease_expires_at=:expires, attempt_count=attempt_count+1,
                              issued_rule_id=:rule, game=:game, retry_after=NULL,
                              last_error_code=NULL, last_error_message=NULL, last_error_at=NULL
                            WHERE image_id=:id
                            """, key(candidate).addValue("expires", timestamp(expires)).addValue("rule", rule.id())
                            .addValue("game", candidate.gameCode()));
                    claimed.add(candidate);
                }
                if (!candidates.isEmpty()) issuedCounts.put(rule.id(), candidates.size());
            }
            activity.issued(issuedCounts, claimNow);
            return List.copyOf(claimed);
        });
    }

    public boolean hasEligiblePending(AiSettings settings, Instant now) {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!settings.enabled()) {
            return false;
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource("now", timestamp(now));
        StringBuilder sql = eligiblePendingSql(parameters, "1", now);
        boolean hasRule = false;
        sql.append(" AND (");
        int ruleIndex = 0;
        for (AiRule rule : settings.rules()) {
            if (!rule.enabled()) {
                continue;
            }
            if (hasRule) {
                sql.append(" OR ");
            }
            sql.append("(TRUE");
            appendRuleConditions(sql, parameters, rule, "_" + ruleIndex++);
            sql.append(')');
            hasRule = true;
        }
        if (!hasRule) {
            return false;
        }
        sql.append(") ORDER BY ai.file_created_at, ai.image_id LIMIT 1");
        return !jdbc.queryForList(sql.toString(), parameters).isEmpty();
    }

    private void recoverExpired() {
        jdbc.update("""
                UPDATE ai_review_task SET status='PENDING', claim_id=NULL, lease_expires_at=NULL,
                  retry_after=NULL, last_error_code='LEASE_EXPIRED',
                  last_error_message='Previous AI claim expired without an accepted result', last_error_at=clock_timestamp()
                WHERE image_id IN (
                  %s FOR UPDATE SKIP LOCKED
                )
                """.formatted(EXPIRED_SELECTION.replace(":now", "CURRENT_TIMESTAMP")), new MapSqlParameterSource());
    }

    public List<AiRuleStatistics> ruleStatistics(AiSettings settings, Instant now) {
        if (settings.rules().isEmpty()) return List.of();
        var parameters = new MapSqlParameterSource("now", timestamp(now))
                .addValue("ruleIds", settings.rules().stream().map(AiRule::id).toList());
        StringBuilder owner = new StringBuilder("CASE");
        List<String> conditions = new ArrayList<>();
        int index = 0;
        for (AiRule rule : settings.rules()) {
            if (!settings.enabled() || !rule.enabled()) continue;
            String suffix = "_" + index++;
            var condition = new StringBuilder("TRUE");
            appendRuleConditions(condition, parameters, rule, suffix);
            conditions.add("(" + condition + ")");
            owner.append(" WHEN ").append(condition);
            owner.append(" THEN CAST(:ruleId").append(suffix).append(" AS uuid)");
            parameters.addValue("ruleId" + suffix, rule.id());
        }
        String assignment = index == 0 ? "NULL::uuid" : owner.append(" ELSE NULL::uuid END").toString();
        // Read-only preview of the same bounded recovery performed before claim; no leases are changed.
        // Disjoint branches keep the PENDING partial indexes usable; an OR with recovery
        // made PostgreSQL scan the entire task table and spill an oversized hash join.
        // Expose selective predicates to the planner; CASE alone hides indexable rule conditions.
        String scope = " /* active rule scope */ AND (" + String.join(" OR ", conditions) + ") /* end scope */ ";
        String pending = index == 0 ? "SELECT NULL::uuid AS rule_id WHERE FALSE"
                : eligiblePendingSql(parameters, assignment + " AS rule_id", now, false, true) + scope
                + " UNION ALL " + eligiblePendingSql(parameters, assignment + " AS rule_id", now, true, true) + scope;
        String sql = "WITH recoverable AS (" + EXPIRED_SELECTION + "), remaining AS (" + pending + """
                ), counts AS (
                  SELECT rule_id, COUNT(*) AS remaining, 0::bigint AS processing,
                         0::bigint AS completed, 0::bigint AS failed
                  FROM remaining WHERE rule_id IS NOT NULL GROUP BY rule_id
                  UNION ALL
                  SELECT issued_rule_id, 0,
                         COUNT(*) FILTER (WHERE status='PROCESSING'),
                         COUNT(*) FILTER (WHERE status='COMPLETED'),
                         COUNT(*) FILTER (WHERE status='FAILED')
                  FROM ai_review_task
                  WHERE issued_rule_id IN (:ruleIds) AND status IN ('PROCESSING','COMPLETED','FAILED')
                  GROUP BY issued_rule_id
                )
                SELECT rule_id, SUM(remaining) AS remaining, SUM(processing) AS processing,
                       SUM(completed) AS completed, SUM(failed) AS failed
                FROM counts GROUP BY rule_id
                """;
        var counts = jdbc.query(sql, parameters, (rs, row) -> new AiRuleStatistics(
                rs.getObject("rule_id", UUID.class), rs.getLong("remaining"), rs.getLong("processing"),
                rs.getLong("completed"), rs.getLong("failed"))).stream().collect(
                java.util.stream.Collectors.toMap(AiRuleStatistics::ruleId, value -> value));
        return settings.rules().stream().map(rule -> counts.getOrDefault(rule.id(),
                new AiRuleStatistics(rule.id(), 0, 0, 0, 0))).toList();
    }

    public void complete(String imageId, AiResult result) {
        transactions.executeWithoutResult(tx -> {
            disagreements.lockImage(imageId);
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
            dailyStatistics.incrementAi(now, result.valid());
            disagreements.capture(imageId);
            activity.result(imageId, now);
        });
    }

    public void reject(AiReject reject) {
        transactions.executeWithoutResult(tx -> {
            disagreements.lockImage(reject.imageId());
            var rows = jdbc.query("SELECT status, claim_id, lease_expires_at FROM ai_review_task WHERE image_id=:id FOR UPDATE",
                    new MapSqlParameterSource("id", reject.imageId()), (rs, row) -> new StoredClaim(
                            rs.getString("status"), rs.getObject("claim_id", UUID.class), instant(rs, "lease_expires_at")));
            if (rows.isEmpty()) throw new AiQueueException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "AI task does not exist");

            var previous = jdbc.query(
                    "SELECT message FROM ai_task_rejection WHERE image_id=:id AND claim_id=:claim FOR UPDATE",
                    key(reject),
                    (rs, row) -> rs.getString("message")
            );
            if (!previous.isEmpty()) {
                if (!reject.message().equals(previous.getFirst())) {
                    throw new AiQueueException(HttpStatus.CONFLICT, "RESULT_CONFLICT", "This claim already has a different rejection");
                }
                return;
            }

            StoredClaim stored = rows.getFirst();
            if ("COMPLETED".equals(stored.status) && reject.claimId().equals(stored.claimId)) {
                throw new AiQueueException(HttpStatus.CONFLICT, "RESULT_CONFLICT", "This claim already has a result");
            }
            if (!reject.claimId().equals(stored.claimId)) throw stale();
            Instant now = databaseNow();
            if (!"PROCESSING".equals(stored.status) || stored.expires == null || !stored.expires.isAfter(now)) {
                throw stale();
            }

            jdbc.update("""
                    INSERT INTO ai_task_rejection (id, image_id, claim_id, message, rejected_at)
                    VALUES (:rejectionId, :id, :claim, :message, :rejectedAt)
                    """, key(reject).addValue("rejectionId", UUID.randomUUID())
                    .addValue("message", reject.message()).addValue("rejectedAt", timestamp(now)));
            jdbc.update("""
                    UPDATE ai_review_task SET status='FAILED', claim_id=NULL, lease_expires_at=NULL,
                      retry_after=NULL, valid=NULL, verdict=NULL, certainty=NULL, confidence=NULL,
                      message=NULL, checked_at=NULL, last_error_code='AI_REJECTED',
                      last_error_message=:message, last_error_at=:rejectedAt
                    WHERE image_id=:id
                    """, new MapSqlParameterSource("id", reject.imageId())
                    .addValue("message", reject.message()).addValue("rejectedAt", timestamp(now)));
            activity.error(reject.imageId());
        });
    }

    public void preparationFailed(AiClaim claim, boolean permanent, String code) {
        transactions.executeWithoutResult(tx -> {
            int changed = jdbc.update("""
                UPDATE ai_review_task SET status=:status, claim_id=NULL, lease_expires_at=NULL,
                  retry_after=CASE WHEN :permanent THEN NULL ELSE clock_timestamp()+INTERVAL '30 seconds' END,
                  last_error_code=:code, last_error_message=:code, last_error_at=clock_timestamp()
                WHERE image_id=:id AND claim_id=:claim AND status='PROCESSING' AND lease_expires_at > clock_timestamp()
                """, key(claim).addValue("status", permanent ? "FAILED" : "PENDING")
                .addValue("permanent", permanent).addValue("code", code));
            if (changed != 0) activity.error(claim.imageId());
        });
    }

    public AiResultDetails details(String imageId) {
        return jdbc.query("""
                SELECT ai.*, rule.name AS issued_rule_name FROM ai_review_task ai
                LEFT JOIN ai_selection_rule rule ON rule.id=ai.issued_rule_id WHERE ai.image_id=:id
                """, new MapSqlParameterSource("id", imageId),
                AiJdbcMapping::resultDetails).stream().findFirst().orElse(null);
    }

    public Instant databaseNow() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private StringBuilder eligiblePendingSql(
            MapSqlParameterSource parameters,
            String projection,
            Instant now
    ) {
        return eligiblePendingSql(parameters, projection, now, false, false);
    }

    private StringBuilder eligiblePendingSql(MapSqlParameterSource parameters, String projection,
                                             Instant now, boolean recovered, boolean statistics) {
        // IDs are SHA-256 hex (ImageId), not natural-language text. Bytewise equality uses
        // the statistics indexes without expensive locale comparisons; claim ordering is unchanged.
        String imageJoin = statistics ? "ia.id COLLATE \"C\"=ai.image_id COLLATE \"C\"" : "ia.id=ai.image_id";
        StringBuilder sql = new StringBuilder("""
                SELECT %s
                FROM ai_review_task ai JOIN image_asset ia ON %s %s
                WHERE %s
                  AND (ai.file_available OR ai.cloud_available_at IS NOT NULL)
                """.formatted(projection, imageJoin, recovered ? "JOIN recoverable r ON r.image_id=ai.image_id" : "", recovered
                ? "TRUE"
                : "ai.status='PENDING' AND (ai.retry_after IS NULL OR ai.retry_after <= :now)"));
        if (b2.enabled()) {
            parameters.addValue("metadataCutoff", timestamp(now.minus(b2.metadataRetention())));
            sql.append("""
                    AND (ai.file_available OR ai.cloud_available_at > :metadataCutoff)
                    AND (ia.file_available OR NULLIF(BTRIM(ia.cloud_object_key),'') IS NOT NULL)
                    AND (ia.file_available OR ia.cloud_uploaded_at > :metadataCutoff)
                    """);
        } else {
            sql.append(" AND ai.file_available=TRUE AND ia.file_available=TRUE ");
        }
        return sql;
    }

    private static void appendRuleConditions(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            AiRule rule,
            String suffix
    ) {
        condition(sql, parameters, "ai.game_code = :gameCode" + suffix,
                "gameCode" + suffix, rule.gameCode());
        condition(sql, parameters, "ai.file_created_at >= :createdFrom" + suffix,
                "createdFrom" + suffix, timestamp(rule.createdFrom()));
        condition(sql, parameters, "ai.file_created_at < :createdTo" + suffix,
                "createdTo" + suffix, timestamp(rule.createdTo()));
        condition(sql, parameters, "ai.token_id = :tokenId" + suffix,
                "tokenId" + suffix, rule.tokenId());
        condition(sql, parameters, "ai.session_id = :sessionId" + suffix,
                "sessionId" + suffix, rule.sessionId());
        condition(sql, parameters, "ai.has_user_hand = :hasUserHand" + suffix,
                "hasUserHand" + suffix, rule.hasUserHand());
    }

    private static MapSqlParameterSource key(AiClaim claim) {
        return new MapSqlParameterSource("id", claim.imageId()).addValue("claim", claim.claimId());
    }
    private static MapSqlParameterSource key(AiReject reject) {
        return new MapSqlParameterSource("id", reject.imageId()).addValue("claim", reject.claimId());
    }
    private static void condition(StringBuilder sql, MapSqlParameterSource p, String predicate, String name, Object value) {
        if (value != null) { sql.append(" AND ").append(predicate); p.addValue(name, value); }
    }
    private static AiQueueException stale() {
        return new AiQueueException(HttpStatus.CONFLICT, "STALE_CLAIM", "Claim expired or was replaced");
    }
    private record StoredResult(String status, UUID claimId, Instant expires, Boolean valid, String verdict,
                                Integer certainty, Integer confidence, String message) {}
    private record StoredClaim(String status, UUID claimId, Instant expires) {}
}
