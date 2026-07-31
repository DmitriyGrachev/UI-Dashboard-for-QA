package com.introlabsystems.recognitionvalidator.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class AdminStatisticsService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminStatisticsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<AdminOperatorStatistics> lastSevenDays() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(7));
        Instant todayStart = now.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        List<AdminOperatorStatistics> rows = jdbc.query("""
                SELECT
                    u.id,
                    u.username,
                    u.enabled,
                    u.created_at,
                    COUNT(rt.image_id) FILTER (
                        WHERE rt.reviewed_at >= ?
                    ) AS today,
                    COUNT(rt.image_id) AS total,
                    COUNT(rt.image_id) FILTER (
                        WHERE rt.decision = 'ACCEPTED'
                    ) AS accepted,
                    COUNT(rt.image_id) FILTER (
                        WHERE rt.decision = 'REJECTED'
                    ) AS rejected
                FROM app_user u
                LEFT JOIN review_task rt
                       ON rt.assigned_to = u.id
                      AND rt.status = 'COMPLETED'
                      AND rt.reviewed_at >= ?
                WHERE u.role = 'OPERATOR'
                GROUP BY u.id, u.username, u.enabled, u.created_at
                ORDER BY u.enabled DESC, LOWER(u.username), u.id
                """,
                (resultSet, rowNumber) -> new AdminOperatorStatistics(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getLong("today"),
                        resultSet.getLong("total"),
                        resultSet.getLong("accepted"),
                        resultSet.getLong("rejected"),
                        0
                ),
                Timestamp.from(todayStart),
                Timestamp.from(cutoff)
        );

        long maximum = rows.stream()
                .mapToLong(AdminOperatorStatistics::total)
                .max()
                .orElse(0);
        if (maximum == 0) {
            return rows;
        }
        return rows.stream()
                .map(row -> new AdminOperatorStatistics(
                        row.operatorId(),
                        row.username(),
                        row.enabled(),
                        row.createdAt(),
                        row.today(),
                        row.total(),
                        row.accepted(),
                        row.rejected(),
                        (int) Math.round(row.total() * 100.0 / maximum)
                ))
                .toList();
    }
}
