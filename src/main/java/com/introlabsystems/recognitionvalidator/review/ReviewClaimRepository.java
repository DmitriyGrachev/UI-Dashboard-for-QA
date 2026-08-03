package com.introlabsystems.recognitionvalidator.review;

import com.introlabsystems.recognitionvalidator.image.ParseStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReviewClaimRepository {

    private static final String ITEM_COLUMNS = """
            SELECT ia.id, ia.file_name, ia.game_code, ia.session_id,
                   ia.dealer_cards, ia.active_user_cards, ia.inactive_user_cards,
                   ia.payload_raw, ia.buttons_raw, ia.is_notification,
                   ia.has_stand, ia.has_hit, ia.has_double, ia.has_split,
                   ia.has_surrender, ia.file_created_at, ia.processed_at,
                   ia.recognition_duration_ms, ia.parse_status
            FROM review_task rt
            JOIN image_asset ia ON ia.id = rt.image_id
            """;
    private static final RowMapper<ReviewItem> ITEM_MAPPER = ReviewClaimRepository::mapItem;

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewClaimRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ReviewQueueResult claim(
            UUID operatorId,
            ReviewFilters filters,
            Instant now,
            Duration leaseDuration,
            boolean replaceCurrent,
            boolean includeRemaining
    ) {
        lockOperator(operatorId);
        releaseExpiredAndUnavailable(now);

        if (replaceCurrent) {
            releaseActiveAssignment(operatorId);
        } else {
            Optional<ReviewItem> active = activeAssignment(operatorId);
            if (active.isPresent()) {
                ReviewQueueSummary summary = includeRemaining
                        ? summarizePending(filters, new MapSqlParameterSource())
                                .including(active.orElseThrow().fileCreatedAt())
                        : null;
                return new ReviewQueueResult(active, summary);
            }
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("operatorId", operatorId)
                .addValue("now", Timestamp.from(now))
                .addValue("leaseExpiresAt", Timestamp.from(now.plus(leaseDuration)));
        ReviewQueueSummary summary = includeRemaining
                ? summarizePending(filters, parameters)
                : null;
        String candidateSql = candidateSql(filters, parameters);
        List<String> candidates = jdbc.query(
                candidateSql,
                parameters,
                (resultSet, rowNumber) -> resultSet.getString("image_id")
        );
        if (candidates.isEmpty()) {
            return new ReviewQueueResult(Optional.empty(), summary);
        }

        String imageId = candidates.getFirst();
        jdbc.update("""
                UPDATE review_task
                SET status = 'ASSIGNED',
                    assigned_to = :operatorId,
                    assigned_at = :now,
                    lease_expires_at = :leaseExpiresAt
                WHERE image_id = :imageId
                """, parameters.addValue("imageId", imageId));
        return new ReviewQueueResult(findItem(imageId), summary);
    }

    public Optional<ReviewItem> findItem(String imageId) {
        List<ReviewItem> items = jdbc.query(
                ITEM_COLUMNS + """
                         WHERE rt.image_id = :imageId
                           AND ia.file_available = TRUE
                        """,
                new MapSqlParameterSource("imageId", imageId),
                ITEM_MAPPER
        );
        return items.stream().findFirst();
    }

    private void lockOperator(UUID operatorId) {
        List<UUID> operators = jdbc.query(
                "SELECT id FROM app_user WHERE id = :operatorId FOR UPDATE",
                new MapSqlParameterSource("operatorId", operatorId),
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)
        );
        if (operators.isEmpty()) {
            throw new IllegalArgumentException("Operator does not exist: " + operatorId);
        }
    }

    private void releaseExpiredAndUnavailable(Instant now) {
        jdbc.update("""
                UPDATE review_task rt
                SET status = 'PENDING',
                    assigned_to = NULL,
                    assigned_at = NULL,
                    lease_expires_at = NULL
                WHERE rt.status = 'ASSIGNED'
                  AND (
                      rt.lease_expires_at <= :now
                      OR NOT EXISTS (
                          SELECT 1
                          FROM image_asset ia
                          WHERE ia.id = rt.image_id
                            AND ia.file_available = TRUE
                      )
                  )
                """, new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    private Optional<ReviewItem> activeAssignment(UUID operatorId) {
        List<ReviewItem> items = jdbc.query(
                ITEM_COLUMNS + """
                         WHERE rt.status = 'ASSIGNED'
                           AND rt.assigned_to = :operatorId
                           AND ia.file_available = TRUE
                         LIMIT 1
                        """,
                new MapSqlParameterSource("operatorId", operatorId),
                ITEM_MAPPER
        );
        return items.stream().findFirst();
    }

    private void releaseActiveAssignment(UUID operatorId) {
        jdbc.update("""
                UPDATE review_task
                SET status = 'PENDING',
                    assigned_to = NULL,
                    assigned_at = NULL,
                    lease_expires_at = NULL
                WHERE status = 'ASSIGNED'
                  AND assigned_to = :operatorId
                """, new MapSqlParameterSource("operatorId", operatorId));
    }

    private ReviewQueueSummary summarizePending(
            ReviewFilters filters,
            MapSqlParameterSource parameters
    ) {
        StringBuilder sql = pendingSql("""
                COUNT(*) AS remaining,
                MIN(ia.file_created_at) AS oldest_created_at,
                MAX(ia.file_created_at) AS newest_created_at
                """);
        appendFilters(sql, filters, parameters);
        return jdbc.queryForObject(sql.toString(), parameters, (resultSet, rowNumber) -> {
            Timestamp oldest = resultSet.getTimestamp("oldest_created_at");
            Timestamp newest = resultSet.getTimestamp("newest_created_at");
            return new ReviewQueueSummary(
                    resultSet.getLong("remaining"),
                    oldest == null ? null : oldest.toInstant(),
                    newest == null ? null : newest.toInstant()
            );
        });
    }

    private String candidateSql(
            ReviewFilters filters,
            MapSqlParameterSource parameters
    ) {
        StringBuilder sql = pendingSql("rt.image_id");
        appendFilters(sql, filters, parameters);
        sql.append("""
                 ORDER BY ia.file_created_at ASC, ia.id ASC
                 FOR UPDATE OF rt SKIP LOCKED
                 LIMIT 1
                """);
        return sql.toString();
    }

    private StringBuilder pendingSql(String projection) {
        return new StringBuilder("""
                SELECT %s
                FROM review_task rt
                JOIN image_asset ia ON ia.id = rt.image_id
                WHERE rt.status = 'PENDING'
                  AND ia.file_available = TRUE
                """.formatted(projection));
    }

    private void appendFilters(
            StringBuilder sql,
            ReviewFilters filters,
            MapSqlParameterSource parameters
    ) {
        if (filters.createdFrom() != null) {
            sql.append(" AND ia.file_created_at >= :createdFrom");
            parameters.addValue("createdFrom", Timestamp.from(filters.createdFrom()));
        }
        if (filters.createdTo() != null) {
            sql.append(" AND ia.file_created_at < :createdTo");
            parameters.addValue("createdTo", Timestamp.from(filters.createdTo()));
        }
        if (hasText(filters.sessionId())) {
            sql.append(" AND ia.session_id = :sessionId");
            parameters.addValue("sessionId", filters.sessionId());
        }
        if (hasText(filters.gameCode())) {
            sql.append(" AND ia.game_code = :gameCode");
            parameters.addValue("gameCode", filters.gameCode());
        }
        if (filters.notification() != null) {
            sql.append(" AND ia.is_notification = :notification");
            parameters.addValue("notification", filters.notification());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ReviewItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp processedAt = resultSet.getTimestamp("processed_at");
        return new ReviewItem(
                resultSet.getString("id"),
                resultSet.getString("file_name"),
                resultSet.getString("game_code"),
                resultSet.getString("session_id"),
                resultSet.getString("dealer_cards"),
                resultSet.getString("active_user_cards"),
                resultSet.getString("inactive_user_cards"),
                resultSet.getString("payload_raw"),
                resultSet.getString("buttons_raw"),
                resultSet.getBoolean("is_notification"),
                resultSet.getBoolean("has_stand"),
                resultSet.getBoolean("has_hit"),
                resultSet.getBoolean("has_double"),
                resultSet.getBoolean("has_split"),
                resultSet.getBoolean("has_surrender"),
                resultSet.getTimestamp("file_created_at").toInstant(),
                processedAt == null ? null : processedAt.toInstant(),
                resultSet.getObject("recognition_duration_ms", Long.class),
                ParseStatus.valueOf(resultSet.getString("parse_status"))
        );
    }
}
