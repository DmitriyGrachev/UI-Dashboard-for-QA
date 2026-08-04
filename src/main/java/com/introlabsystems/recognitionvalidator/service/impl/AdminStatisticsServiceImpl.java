package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.service.AdminStatisticsService;
import com.introlabsystems.recognitionvalidator.statistics.AdminOperatorStatistics;
import com.introlabsystems.recognitionvalidator.statistics.AdminStatisticsPage;
import com.introlabsystems.recognitionvalidator.statistics.DailyReviewCount;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminStatisticsServiceImpl implements AdminStatisticsService {

    static final int PAGE_SIZE = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Override
    public AdminStatisticsPage page(int requestedPage) {
        Long counted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE role = 'OPERATOR'",
                new MapSqlParameterSource(),
                Long.class
        );
        long totalOperators = counted == null ? 0 : counted;
        int totalPages = (int) Math.ceil(totalOperators / (double) PAGE_SIZE);
        int page = totalPages == 0
                ? 0
                : Math.min(Math.max(0, requestedPage), totalPages - 1);
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start = today.minusDays(6);

        List<AdminOperatorStatistics> operators = jdbc.query("""
                WITH selected_users AS (
                    SELECT id, username, enabled, created_at
                    FROM app_user
                    WHERE role = 'OPERATOR'
                    ORDER BY enabled DESC, LOWER(username), id
                    LIMIT :limit OFFSET :offset
                )
                SELECT
                    u.id,
                    u.username,
                    u.enabled,
                    u.created_at,
                    COALESCE(SUM(ds.total_checked) FILTER (
                        WHERE ds.statistics_date = :today
                    ), 0) AS today,
                    COALESCE(SUM(ds.total_checked) FILTER (
                        WHERE ds.statistics_date BETWEEN :start AND :today
                    ), 0) AS last_seven_days,
                    COALESCE(SUM(ds.total_checked), 0) AS all_time,
                    COALESCE(SUM(ds.matched_count) FILTER (
                        WHERE ds.statistics_date BETWEEN :start AND :today
                    ), 0) AS matched,
                    COALESCE(SUM(ds.not_matched_count) FILTER (
                        WHERE ds.statistics_date BETWEEN :start AND :today
                    ), 0) AS not_matched
                FROM selected_users u
                LEFT JOIN operator_daily_statistics ds ON ds.operator_id = u.id
                GROUP BY u.id, u.username, u.enabled, u.created_at
                ORDER BY u.enabled DESC, LOWER(u.username), u.id
                """, new MapSqlParameterSource()
                        .addValue("limit", PAGE_SIZE)
                        .addValue("offset", page * PAGE_SIZE)
                        .addValue("today", today)
                        .addValue("start", start),
                (resultSet, rowNumber) -> new AdminOperatorStatistics(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getLong("today"),
                        resultSet.getLong("last_seven_days"),
                        resultSet.getLong("all_time"),
                        resultSet.getLong("matched"),
                        resultSet.getLong("not_matched"),
                        List.of(),
                        0
                ));

        Map<UUID, Map<LocalDate, DailyReviewCount>> daily = dailyFor(
                operators.stream().map(AdminOperatorStatistics::operatorId).toList(),
                start,
                today
        );
        List<AdminOperatorStatistics> withDaily = operators.stream()
                .map(operator -> withDaily(operator, daily, start))
                .toList();
        long maximum = withDaily.stream()
                .mapToLong(AdminOperatorStatistics::lastSevenDays)
                .max()
                .orElse(0);
        List<AdminOperatorStatistics> result = withDaily.stream()
                .map(operator -> withBarPercent(operator, maximum))
                .toList();
        return new AdminStatisticsPage(result, page, totalPages, totalOperators);
    }

    private Map<UUID, Map<LocalDate, DailyReviewCount>> dailyFor(
            List<UUID> operatorIds,
            LocalDate start,
            LocalDate today
    ) {
        if (operatorIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<LocalDate, DailyReviewCount>> result = new HashMap<>();
        jdbc.query("""
                SELECT operator_id, statistics_date, total_checked,
                       matched_count, not_matched_count
                FROM operator_daily_statistics
                WHERE operator_id IN (:operatorIds)
                  AND statistics_date BETWEEN :start AND :today
                ORDER BY operator_id, statistics_date
                """, new MapSqlParameterSource()
                        .addValue("operatorIds", operatorIds)
                        .addValue("start", start)
                        .addValue("today", today),
                resultSet -> {
                    UUID operatorId = resultSet.getObject("operator_id", UUID.class);
                    LocalDate date = resultSet.getObject("statistics_date", LocalDate.class);
                    result.computeIfAbsent(operatorId, ignored -> new HashMap<>())
                            .put(date, new DailyReviewCount(
                                    date,
                                    resultSet.getLong("total_checked"),
                                    resultSet.getLong("matched_count"),
                                    resultSet.getLong("not_matched_count")
                            ));
                });
        return result;
    }

    private AdminOperatorStatistics withDaily(
            AdminOperatorStatistics operator,
            Map<UUID, Map<LocalDate, DailyReviewCount>> dailyByOperator,
            LocalDate start
    ) {
        Map<LocalDate, DailyReviewCount> stored = dailyByOperator.getOrDefault(
                operator.operatorId(),
                Map.of()
        );
        List<DailyReviewCount> daily = new ArrayList<>(7);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = start.plusDays(offset);
            daily.add(stored.getOrDefault(date, new DailyReviewCount(date, 0, 0, 0)));
        }
        return copy(operator, daily, 0);
    }

    private AdminOperatorStatistics withBarPercent(
            AdminOperatorStatistics operator,
            long maximum
    ) {
        int percent = maximum == 0
                ? 0
                : (int) Math.round(operator.lastSevenDays() * 100.0 / maximum);
        return copy(operator, operator.daily(), percent);
    }

    private AdminOperatorStatistics copy(
            AdminOperatorStatistics source,
            List<DailyReviewCount> daily,
            int barPercent
    ) {
        return new AdminOperatorStatistics(
                source.operatorId(),
                source.username(),
                source.enabled(),
                source.createdAt(),
                source.today(),
                source.lastSevenDays(),
                source.allTime(),
                source.matched(),
                source.notMatched(),
                daily,
                barPercent
        );
    }
}
