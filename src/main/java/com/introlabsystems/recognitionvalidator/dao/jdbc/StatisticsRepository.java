package com.introlabsystems.recognitionvalidator.dao.jdbc;

import com.introlabsystems.recognitionvalidator.model.value.DailyReviewCount;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class StatisticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StatisticsCounts countForOperator(
            UUID operatorId,
            LocalDate today,
            LocalDate from,
            LocalDate to
    ) {
        return jdbc.queryForObject("""
                SELECT
                    COALESCE(SUM(total_checked) FILTER (
                        WHERE statistics_date = :today
                    ), 0) AS today,
                    COALESCE(SUM(total_checked) FILTER (
                        WHERE statistics_date BETWEEN :from AND :to
                    ), 0) AS last_seven_days,
                    COALESCE(SUM(total_checked), 0) AS all_time,
                    COALESCE(SUM(matched_count) FILTER (
                        WHERE statistics_date BETWEEN :from AND :to
                    ), 0) AS matched,
                    COALESCE(SUM(not_matched_count) FILTER (
                        WHERE statistics_date BETWEEN :from AND :to
                    ), 0) AS not_matched
                FROM operator_daily_statistics
                WHERE operator_id = :operatorId
                """,
                new MapSqlParameterSource()
                        .addValue("operatorId", operatorId)
                        .addValue("today", today)
                        .addValue("from", from)
                        .addValue("to", to),
                (resultSet, rowNumber) -> new StatisticsCounts(
                        resultSet.getLong("today"),
                        resultSet.getLong("last_seven_days"),
                        resultSet.getLong("all_time"),
                        resultSet.getLong("matched"),
                        resultSet.getLong("not_matched")
                )
        );
    }

    public List<DailyReviewCount> dailyForOperator(
            UUID operatorId,
            LocalDate from,
            LocalDate to
    ) {
        return jdbc.query("""
                SELECT statistics_date, total_checked, matched_count, not_matched_count
                FROM operator_daily_statistics
                WHERE operator_id = :operatorId
                  AND statistics_date BETWEEN :from AND :to
                ORDER BY statistics_date
                """, new MapSqlParameterSource()
                        .addValue("operatorId", operatorId)
                        .addValue("from", from)
                        .addValue("to", to),
                (resultSet, rowNumber) -> new DailyReviewCount(
                        resultSet.getObject("statistics_date", LocalDate.class),
                        resultSet.getLong("total_checked"),
                        resultSet.getLong("matched_count"),
                        resultSet.getLong("not_matched_count")
                ));
    }

    public record StatisticsCounts(
            long today,
            long lastSevenDays,
            long allTime,
            long matched,
            long notMatched
    ) {
    }
}
