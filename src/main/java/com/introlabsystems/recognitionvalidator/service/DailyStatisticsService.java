package com.introlabsystems.recognitionvalidator.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DailyStatisticsService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 15)
    public Daily read(LocalDate requestedDate) {
        Instant now = clock.instant();
        LocalDate date = requestedDate == null ? now.atZone(ZoneOffset.UTC).toLocalDate() : requestedDate;
        if (date.getYear() < 1 || date.getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date year must be 0001..9999");
        }
        List<Operator> people = jdbc.query("""
                SELECT u.id, u.username, ds.total_checked, ds.matched_count, ds.not_matched_count
                FROM operator_daily_statistics ds JOIN app_user u ON u.id=ds.operator_id
                WHERE ds.statistics_date=? ORDER BY u.username, u.id
                """, (rs, row) -> new Operator(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getLong("total_checked"), rs.getLong("matched_count"), rs.getLong("not_matched_count")), date);
        Operators operators = new Operators(people.stream().mapToLong(Operator::total).sum(),
                people.stream().mapToLong(Operator::accepted).sum(),
                people.stream().mapToLong(Operator::rejected).sum(), people);
        long[] ai = jdbc.query("""
                SELECT total_checked, matched_count, not_matched_count FROM ai_daily_statistics WHERE statistics_date=?
                """, rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)} : new long[3], date);
        Confidence confidence = jdbc.queryForObject("""
                SELECT COUNT(*) AS retained,
                       COUNT(*) FILTER (WHERE confidence BETWEEN 0 AND 49) AS below_50,
                       COUNT(*) FILTER (WHERE confidence BETWEEN 50 AND 79) AS from_50_to_79,
                       COUNT(*) FILTER (WHERE confidence BETWEEN 80 AND 94) AS from_80_to_94,
                       COUNT(*) FILTER (WHERE confidence BETWEEN 95 AND 100) AS from_95_to_100,
                       COUNT(*) FILTER (WHERE confidence IS NULL) AS unknown
                FROM ai_review_task WHERE status='COMPLETED' AND checked_at>=? AND checked_at<?
                """, (rs, row) -> new Confidence(rs.getLong("below_50"), rs.getLong("from_50_to_79"),
                rs.getLong("from_80_to_94"), rs.getLong("from_95_to_100"), rs.getLong("unknown"),
                rs.getLong("retained"), rs.getLong("retained") == ai[0]),
                Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        return new Daily(date, "UTC", now, operators, new Ai(ai[0], ai[1], ai[2], confidence));
    }

    public record Daily(LocalDate date, String timezone, Instant generatedAt, Operators operators, Ai ai) {}
    public record Operators(long total, long accepted, long rejected, List<Operator> byOperator) {}
    public record Operator(UUID id, String username, long total, long accepted, long rejected) {}
    public record Ai(long total, long matched, long mismatched, Confidence confidence) {}
    public record Confidence(long below50, long from50To79, long from80To94, long from95To100,
                             long unknown, long retainedResults, boolean completeCoverage) {}
}
