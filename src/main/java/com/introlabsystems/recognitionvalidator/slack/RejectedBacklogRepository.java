package com.introlabsystems.recognitionvalidator.slack;

import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RejectedBacklogRepository {

    private static final String PREDICATE = """
            FROM review_task rt
            JOIN image_asset ia ON ia.id = rt.image_id
            LEFT JOIN app_user au ON au.id = rt.assigned_to
            WHERE rt.status = 'COMPLETED'
              AND rt.decision = 'REJECTED'
              AND rt.rejected_downloaded_at IS NULL
              AND ia.file_available = TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RejectedBacklogSnapshot snapshot(int limit) {
        long count = jdbc.queryForObject(
                "SELECT COUNT(*) " + PREDICATE,
                new MapSqlParameterSource(),
                Long.class
        );
        List<RejectedBacklogItem> items = jdbc.query("""
                SELECT ia.file_name, ia.game_code, ia.session_id,
                       ia.dealer_cards, ia.active_user_cards, ia.inactive_user_cards,
                       ia.is_notification, ia.buttons_raw, ia.parse_status,
                       au.username AS operator_name, rt.reviewed_at
                """ + PREDICATE + """
                ORDER BY ia.processed_at, rt.image_id
                LIMIT :limit
                """, new MapSqlParameterSource("limit", Math.max(0, limit)),
                RejectedBacklogRepository::mapItem);
        return new RejectedBacklogSnapshot(count, items);
    }

    private static RejectedBacklogItem mapItem(ResultSet resultSet, int rowNumber)
            throws SQLException {
        Timestamp reviewedAt = resultSet.getTimestamp("reviewed_at");
        String parseStatus = resultSet.getString("parse_status");
        return new RejectedBacklogItem(
                resultSet.getString("file_name"),
                resultSet.getString("game_code"),
                resultSet.getString("session_id"),
                resultSet.getString("dealer_cards"),
                resultSet.getString("active_user_cards"),
                resultSet.getString("inactive_user_cards"),
                resultSet.getBoolean("is_notification"),
                resultSet.getString("buttons_raw"),
                parseStatus == null ? null : ParseStatus.valueOf(parseStatus),
                resultSet.getString("operator_name"),
                reviewedAt == null ? null : reviewedAt.toInstant()
        );
    }
}
