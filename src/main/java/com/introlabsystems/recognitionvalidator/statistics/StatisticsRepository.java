package com.introlabsystems.recognitionvalidator.statistics;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class StatisticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StatisticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StatisticsCounts countForOperator(
            UUID operatorId,
            Instant todayStart,
            Instant tomorrowStart,
            Instant from,
            Instant to
    ) {
        return jdbc.queryForObject("""
                SELECT
                    COUNT(*) FILTER (
                        WHERE rt.reviewed_at >= :todayStart
                          AND rt.reviewed_at < :tomorrowStart
                    ) AS today,
                    COUNT(*) AS retained_total,
                    COUNT(*) FILTER (
                        WHERE rt.reviewed_at >= :from
                          AND rt.reviewed_at < :to
                    ) AS period_total,
                    COUNT(*) FILTER (
                        WHERE rt.reviewed_at >= :from
                          AND rt.reviewed_at < :to
                          AND rt.decision = 'ACCEPTED'
                    ) AS accepted,
                    COUNT(*) FILTER (
                        WHERE rt.reviewed_at >= :from
                          AND rt.reviewed_at < :to
                          AND rt.decision = 'REJECTED'
                    ) AS rejected
                FROM review_task rt
                WHERE rt.status = 'COMPLETED'
                  AND rt.assigned_to = :operatorId
                """,
                new MapSqlParameterSource()
                        .addValue("operatorId", operatorId)
                        .addValue("todayStart", Timestamp.from(todayStart))
                        .addValue("tomorrowStart", Timestamp.from(tomorrowStart))
                        .addValue("from", Timestamp.from(from))
                        .addValue("to", Timestamp.from(to)),
                (resultSet, rowNumber) -> new StatisticsCounts(
                        resultSet.getLong("today"),
                        resultSet.getLong("retained_total"),
                        resultSet.getLong("period_total"),
                        resultSet.getLong("accepted"),
                        resultSet.getLong("rejected")
                )
        );
    }

    public record StatisticsCounts(
            long today,
            long retainedTotal,
            long periodTotal,
            long accepted,
            long rejected
    ) {
    }
}
