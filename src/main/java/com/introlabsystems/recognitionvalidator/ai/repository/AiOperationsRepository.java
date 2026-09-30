package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.dto.AiFailureSummary;
import com.introlabsystems.recognitionvalidator.ai.dto.AiFailurePage;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleActivitySnapshot;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleStatisticsSnapshot;
import com.introlabsystems.recognitionvalidator.ai.dto.AiOperationsSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

import static com.introlabsystems.recognitionvalidator.ai.mapper.AiJdbcMapping.instant;

@Repository
@RequiredArgsConstructor
public class AiOperationsRepository {
    private final JdbcTemplate jdbc;
    private final AiSettingsRepository settingsRepository;
    private final AiTaskRepository tasks;
    private final AiRuleActivityRepository activity;

    @Transactional(readOnly = true, timeout = 5)
    public AiFailurePage failureLog(boolean aiOnly, Instant beforeAt, String beforeId,
                                   java.util.UUID ruleId, String errorCode, boolean errorMissing, boolean ruleMissing,
                                   String gameCode, String errorText, Instant failedFrom, Instant failedTo) {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
        var filters = new StringBuilder();
        AiResultFilterSql.appendDiagnostics(filters, parameters, ruleId, errorCode, errorMissing, ruleMissing);
        if (aiOnly) filters.append(" AND last_error_code='AI_REJECTED'");
        if (gameCode != null && !gameCode.isBlank()) {
            filters.append(" AND ai.game_code=:gameCode");
            parameters.addValue("gameCode", gameCode.trim());
        }
        if (errorText != null && !errorText.isBlank()) {
            filters.append(" AND POSITION(LOWER(:errorText) IN LOWER(COALESCE(ai.last_error_message,''))) > 0");
            parameters.addValue("errorText", errorText.trim());
        }
        if (failedFrom != null) {
            filters.append(" AND ai.last_error_at >= :failedFrom");
            parameters.addValue("failedFrom", Timestamp.from(failedFrom));
        }
        if (failedTo != null) {
            filters.append(" AND ai.last_error_at < :failedTo");
            parameters.addValue("failedTo", Timestamp.from(failedTo));
        }
        String cursor = "";
        if (beforeAt != null) {
            cursor = "AND (COALESCE(last_error_at,file_created_at),image_id) < (:beforeAt,:beforeId)";
            parameters.addValue("beforeAt", Timestamp.from(beforeAt)).addValue("beforeId", beforeId);
        }
        // Sort only the FAILED subset; fetch metadata for at most 21 tasks, without a history COUNT.
        var rows = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
                SELECT t.*,ia.file_name,r.name AS rule_name
                FROM (
                  SELECT image_id,game_code,issued_rule_id,last_error_code,last_error_message,
                         last_error_at,file_created_at,attempt_count
                  FROM ai_review_task ai WHERE status='FAILED' %s %s
                  ORDER BY COALESCE(last_error_at,file_created_at) DESC,image_id DESC LIMIT 21
                ) t
                JOIN image_asset ia ON ia.id=t.image_id
                LEFT JOIN ai_selection_rule r ON r.id=t.issued_rule_id
                ORDER BY COALESCE(t.last_error_at,t.file_created_at) DESC,t.image_id DESC
                """.formatted(filters, cursor), parameters,
                (rs, row) -> new AiFailurePage.Item(rs.getString("image_id"), rs.getString("file_name"),
                        rs.getString("game_code"), rs.getObject("issued_rule_id", java.util.UUID.class),
                        rs.getString("rule_name"), rs.getString("last_error_code"), rs.getString("last_error_message"),
                        instant(rs, "last_error_at"), instant(rs, "file_created_at"), rs.getInt("attempt_count")));
        var items = rows.size() > 20 ? List.copyOf(rows.subList(0, 20)) : rows;
        var last = rows.size() > 20 ? items.getLast() : null;
        return new AiFailurePage(items, last == null ? null : last.failedAt() == null ? last.createdAt() : last.failedAt(),
                last == null ? null : last.imageId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AiFailureSummary failures(Instant now) {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        var groups = jdbc.query("""
                SELECT g.issued_rule_id,r.name,g.last_error_code,g.failed_count
                FROM (
                    SELECT issued_rule_id,last_error_code,COUNT(*) AS failed_count
                    FROM ai_review_task WHERE status='FAILED'
                    GROUP BY issued_rule_id,last_error_code
                ) g
                LEFT JOIN ai_selection_rule r ON r.id=g.issued_rule_id
                ORDER BY g.failed_count DESC,g.issued_rule_id NULLS LAST,g.last_error_code NULLS LAST
                """, (rs, row) -> new AiFailureSummary.Group(rs.getObject("issued_rule_id", java.util.UUID.class),
                rs.getString("name"), rs.getString("last_error_code"), rs.getLong("failed_count")));
        return new AiFailureSummary(now, groups.stream().mapToLong(AiFailureSummary.Group::count).sum(), groups);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AiRuleActivitySnapshot activity(Instant now) {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        var settings = settingsRepository.read();
        return AiRuleActivitySnapshot.from(settings.revision(), now, activity.read(now));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AiRuleStatisticsSnapshot rules(Instant now) {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        AiSettings settings = settingsRepository.read();
        return new AiRuleStatisticsSnapshot(settings.revision(), settings.enabled(), now, tasks.ruleStatistics(settings, now));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AiOperationsSnapshot snapshot(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        AiSettings settings = settingsRepository.read();
        long[] counts = jdbc.queryForObject("""
                SELECT active.processing, active.expired,
                       (SELECT COUNT(*) FROM ai_review_task WHERE status='FAILED') AS failed
                FROM (
                    SELECT COUNT(*) AS processing, COUNT(*) FILTER (WHERE lease_expires_at <= ?) AS expired
                    FROM ai_review_task WHERE status='PROCESSING'
                ) active
                """, (rs, row) -> new long[]{rs.getLong("processing"), rs.getLong("failed"), rs.getLong("expired")},
                Timestamp.from(now));
        Timestamp lastResult = jdbc.queryForObject("SELECT MAX(last_checked_at) FROM ai_daily_statistics", Timestamp.class);
        return new AiOperationsSnapshot(settings.enabled(), tasks.hasEligiblePending(settings, now), counts[0], counts[1], counts[2],
                lastResult == null ? null : lastResult.toInstant());
    }
}
