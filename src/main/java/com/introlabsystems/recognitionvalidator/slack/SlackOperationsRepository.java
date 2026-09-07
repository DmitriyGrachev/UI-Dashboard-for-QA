package com.introlabsystems.recognitionvalidator.slack;

import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.StorageStatusRepository;
import com.introlabsystems.recognitionvalidator.model.value.StorageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class SlackOperationsRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final StorageStatusRepository storage;
    private final B2StorageProperties b2;
    private final AiSettingsRepository aiSettings;
    private final AiTaskRepository aiTasks;
    private final RejectedBacklogRepository rejectedBacklog;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Metrics snapshot(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        setStatementTimeout();
        StorageStatusRepository.Observation b2Observation = storage.observation(
                b2.enabled(), now, b2.metadataRetention());
        StorageStatus b2Status = b2Observation.status();
        AiSettings settings = aiSettings.read();
        long eligiblePending = aiTasks.countEligiblePending(settings, now);
        long processing = number("SELECT COUNT(*) FROM ai_review_task WHERE status='PROCESSING'");
        long expired = namedJdbc.queryForObject("""
                SELECT COUNT(*)
                FROM ai_review_task
                WHERE status='PROCESSING' AND lease_expires_at <= :now
                """, new MapSqlParameterSource("now", timestamp(now)), Long.class);
        Instant lastResult = instant(namedJdbc.queryForObject(
                "SELECT MAX(last_checked_at) FROM ai_daily_statistics",
                new MapSqlParameterSource(), Timestamp.class
        ));
        return new Metrics(
                b2Status,
                settings.enabled(),
                eligiblePending,
                processing,
                expired,
                lastResult,
                b2Observation.repeatedAttempts(),
                b2Observation.lastUpload()
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Daily daily(LocalDate utcDay) {
        Objects.requireNonNull(utcDay, "utcDay must not be null");
        setStatementTimeout();
        var dates = new MapSqlParameterSource()
                .addValue("day", utcDay);
        var operator = namedJdbc.queryForObject("""
                SELECT COALESCE(SUM(total_checked), 0) AS checked,
                       COALESCE(SUM(not_matched_count), 0) AS rejected
                FROM operator_daily_statistics
                WHERE statistics_date = :day
                """, dates, (rs, row) -> new long[]{rs.getLong("checked"), rs.getLong("rejected")});
        var ai = namedJdbc.queryForObject("""
                SELECT COALESCE(SUM(total_checked), 0) AS checked,
                       COALESCE(SUM(matched_count), 0) AS matched,
                       COALESCE(SUM(not_matched_count), 0) AS rejected
                FROM ai_daily_statistics
                WHERE statistics_date = :day
                """, dates, (rs, row) -> new long[]{
                rs.getLong("checked"), rs.getLong("matched"), rs.getLong("rejected")
        });
        var disagreements = namedJdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE ai_matched) AS ai_matched_operator_rejected,
                       COUNT(*) FILTER (WHERE NOT ai_matched) AS ai_unmatched_operator_accepted
                FROM review_disagreement
                WHERE observed_at >= :from AND observed_at < :to
                """, new MapSqlParameterSource()
                .addValue("from", timestamp(utcDay.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .addValue("to", timestamp(utcDay.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())),
                (rs, row) -> new long[]{rs.getLong("ai_matched_operator_rejected"), rs.getLong("ai_unmatched_operator_accepted")});
        return new Daily(operator[0], operator[1], ai[0], ai[1], ai[2], disagreements[0], disagreements[1]);
    }

    @Transactional(readOnly = true, timeout = 5)
    public long pendingRejects() {
        setStatementTimeout();
        return rejectedBacklog.snapshot(0).count();
    }

    private void setStatementTimeout() {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
    }

    private long number(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Metrics(
            StorageStatus b2,
            boolean aiEnabled,
            long aiEligiblePending,
            long aiProcessing,
            long aiExpired,
            Instant aiLastResult,
            long b2RepeatedFailures,
            Instant b2LastUpload
    ) {
    }

    public record Daily(
            long operatorChecked,
            long operatorRejected,
            long aiChecked,
            long aiMatch,
            long aiRejected,
            long aiMatchedOperatorRejected,
            long aiUnmatchedOperatorAccepted
    ) {
    }
}
