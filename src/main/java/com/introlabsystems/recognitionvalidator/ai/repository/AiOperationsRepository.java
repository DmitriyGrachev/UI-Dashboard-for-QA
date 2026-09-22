package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.dto.AiFailureSummary;
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

@Repository
@RequiredArgsConstructor
public class AiOperationsRepository {
    private final JdbcTemplate jdbc;
    private final AiSettingsRepository settingsRepository;
    private final AiTaskRepository tasks;
    private final AiRuleActivityRepository activity;

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
