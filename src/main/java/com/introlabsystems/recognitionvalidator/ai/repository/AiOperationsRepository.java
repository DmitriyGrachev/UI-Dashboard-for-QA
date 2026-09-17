package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleStatistics;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleActivitySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AiOperationsRepository {
    private final JdbcTemplate jdbc;
    private final AiSettingsRepository settingsRepository;
    private final AiTaskRepository tasks;
    private final AiRuleActivityRepository activity;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AiRuleActivitySnapshot activity(Instant now) {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        var settings = settingsRepository.read();
        return AiRuleActivitySnapshot.from(settings.revision(), now, activity.read(now));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public RuleSnapshot rules(Instant now) {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        AiSettings settings = settingsRepository.read();
        return new RuleSnapshot(settings.revision(), settings.enabled(), now, tasks.ruleStatistics(settings, now));
    }

    public record RuleSnapshot(long revision, boolean enabled, Instant generatedAt,
                               List<AiRuleStatistics> rules) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Snapshot snapshot(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        AiSettings settings = settingsRepository.read();
        long[] counts = jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status='PROCESSING') AS processing,
                       COUNT(*) FILTER (WHERE status='FAILED') AS failed,
                       COUNT(*) FILTER (WHERE status='PROCESSING' AND lease_expires_at <= ?) AS expired
                FROM ai_review_task
                """, (rs, row) -> new long[]{rs.getLong("processing"), rs.getLong("failed"), rs.getLong("expired")},
                Timestamp.from(now));
        Timestamp lastResult = jdbc.queryForObject("SELECT MAX(last_checked_at) FROM ai_daily_statistics", Timestamp.class);
        return new Snapshot(settings.enabled(), tasks.hasEligiblePending(settings, now), counts[0], counts[1], counts[2],
                lastResult == null ? null : lastResult.toInstant());
    }

    public record Snapshot(boolean enabled, boolean hasEligiblePending, long processing, long failed,
                           long expired, Instant lastResult) {}
}
