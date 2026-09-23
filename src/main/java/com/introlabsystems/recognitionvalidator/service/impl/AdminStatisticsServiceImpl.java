package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.service.AdminStatisticsService;
import com.introlabsystems.recognitionvalidator.model.value.AdminOperatorStatistics;
import com.introlabsystems.recognitionvalidator.model.value.AdminStatisticsPage;
import com.introlabsystems.recognitionvalidator.model.value.AdminOverviewStatistics;
import com.introlabsystems.recognitionvalidator.model.value.DailyReviewCount;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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
    @Transactional(readOnly = true, timeout = 5)
    public AdminOverviewStatistics overview(int days) {
        if (days != 7 && days != 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Period must be 7 or 30 days");
        }
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        var parameters = new MapSqlParameterSource().addValue("start", today.minusDays(days - 1)).addValue("end", today);
        // Read the retained daily counters, never rescan image or task history for the overview.
        var daily = jdbc.query("""
                SELECT d::date AS day,
                       COALESCE(o.total, 0) AS operator_total,
                       COALESCE(o.accepted, 0) AS accepted, COALESCE(o.rejected, 0) AS rejected,
                       COALESCE(a.total_checked, 0) AS ai_total,
                       COALESCE(a.matched_count, 0) AS matched, COALESCE(a.not_matched_count, 0) AS mismatched
                FROM generate_series(CAST(:start AS date), CAST(:end AS date), interval '1 day') d
                LEFT JOIN (
                    SELECT statistics_date, SUM(total_checked) AS total,
                           SUM(matched_count) AS accepted, SUM(not_matched_count) AS rejected
                    FROM operator_daily_statistics
                    WHERE statistics_date BETWEEN :start AND :end
                    GROUP BY statistics_date
                ) o ON o.statistics_date = d::date
                LEFT JOIN ai_daily_statistics a ON a.statistics_date = d::date
                ORDER BY d
                """, parameters, (rs, row) -> new AdminOverviewStatistics.Day(rs.getObject("day", LocalDate.class),
                rs.getLong("operator_total"), rs.getLong("accepted"), rs.getLong("rejected"),
                rs.getLong("ai_total"), rs.getLong("matched"), rs.getLong("mismatched")));
        return new AdminOverviewStatistics(daily);
    }

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
