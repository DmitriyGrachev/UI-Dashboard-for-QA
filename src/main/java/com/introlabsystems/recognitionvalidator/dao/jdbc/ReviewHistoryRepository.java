package com.introlabsystems.recognitionvalidator.dao.jdbc;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReviewHistoryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public static final int PAGE_SIZE = 25;

    @Transactional(readOnly = true, timeout = 5)
    public List<Entry> recent(UUID operatorId, Instant beforeAt, String beforeId) {
        var parameters = new MapSqlParameterSource("operator", operatorId)
                .addValue("limit", PAGE_SIZE + 1);
        String cursor = "";
        if (beforeAt != null) {
            cursor = " AND (reviewed_at, image_id) < (:beforeAt, :beforeId)";
            parameters.addValue("beforeAt", Timestamp.from(beforeAt)).addValue("beforeId", beforeId);
        }
        return jdbc.query("""
                SELECT rt.image_id, ia.file_name, rt.game_code, rt.decision, rt.reviewed_at
                FROM (
                    SELECT image_id, game_code, decision, reviewed_at FROM review_task
                    WHERE assigned_to=:operator AND status='COMPLETED' AND reviewed_at IS NOT NULL
                    %s
                    ORDER BY reviewed_at DESC, image_id DESC LIMIT :limit
                ) rt
                JOIN image_asset ia ON ia.id=rt.image_id
                ORDER BY rt.reviewed_at DESC, rt.image_id DESC
                """.formatted(cursor), parameters, (rs, row) -> new Entry(rs.getString("image_id"),
                rs.getString("file_name"), rs.getString("game_code"), rs.getString("decision"),
                rs.getTimestamp("reviewed_at").toInstant()));
    }

    public record Entry(String imageId, String fileName, String gameCode, String decision, Instant reviewedAt) {
        private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm:ss 'UTC'", Locale.ENGLISH)
                .withZone(ZoneOffset.UTC);
        public String reviewedAtUtc() { return UTC.format(reviewedAt); }
        public String decisionLabel() {
            return "ACCEPTED".equals(decision) ? "Matches" : "REJECTED".equals(decision) ? "Does not match" : "Not recorded";
        }
    }
}
