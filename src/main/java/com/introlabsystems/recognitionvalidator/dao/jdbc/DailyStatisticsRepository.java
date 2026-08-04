package com.introlabsystems.recognitionvalidator.dao.jdbc;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public class DailyStatisticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DailyStatisticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void increment(UUID operatorId, LocalDate date, boolean matchedDecision) {
        long matched = matchedDecision ? 1 : 0;
        long notMatched = matchedDecision ? 0 : 1;
        jdbc.update("""
                INSERT INTO operator_daily_statistics (
                    operator_id, statistics_date, total_checked,
                    matched_count, not_matched_count
                ) VALUES (
                    :operatorId, :statisticsDate, 1, :matched, :notMatched
                )
                ON CONFLICT (operator_id, statistics_date) DO UPDATE
                SET total_checked = operator_daily_statistics.total_checked + 1,
                    matched_count = operator_daily_statistics.matched_count + EXCLUDED.matched_count,
                    not_matched_count = operator_daily_statistics.not_matched_count + EXCLUDED.not_matched_count
                """, new MapSqlParameterSource()
                .addValue("operatorId", operatorId)
                .addValue("statisticsDate", date)
                .addValue("matched", matched)
                .addValue("notMatched", notMatched));
    }

    public void rebuildFromCompletedTasks() {
        jdbc.update("""
                INSERT INTO operator_daily_statistics (
                    operator_id, statistics_date, total_checked,
                    matched_count, not_matched_count
                )
                SELECT
                    rt.assigned_to,
                    (rt.reviewed_at AT TIME ZONE 'UTC')::date,
                    COUNT(*),
                    COUNT(*) FILTER (WHERE rt.decision = 'ACCEPTED'),
                    COUNT(*) FILTER (WHERE rt.decision = 'REJECTED')
                FROM review_task rt
                WHERE rt.status = 'COMPLETED'
                  AND rt.assigned_to IS NOT NULL
                  AND rt.reviewed_at IS NOT NULL
                GROUP BY rt.assigned_to, (rt.reviewed_at AT TIME ZONE 'UTC')::date
                ON CONFLICT (operator_id, statistics_date) DO NOTHING
                """, new MapSqlParameterSource());
    }
}
