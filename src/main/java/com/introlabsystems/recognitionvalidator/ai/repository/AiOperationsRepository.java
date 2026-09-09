package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
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
