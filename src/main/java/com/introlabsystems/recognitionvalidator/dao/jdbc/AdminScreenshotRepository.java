package com.introlabsystems.recognitionvalidator.dao.jdbc;

import com.introlabsystems.recognitionvalidator.ai.repository.AiResultFilterSql;

import com.introlabsystems.recognitionvalidator.model.enums.AdminReviewState;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.enums.ImageStorageState;
import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotDetails;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotFilters;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotListItem;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotPage;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AdminScreenshotRepository {

    private static final String SEARCH_FROM = """
            FROM review_task rt
            JOIN image_asset ia ON ia.id = rt.image_id
            LEFT JOIN app_user reviewer ON reviewer.id = rt.assigned_to
            WHERE TRUE
            """;
    private static final String SUMMARY_FROM = """
            FROM review_task rt
            LEFT JOIN app_user reviewer ON reviewer.id = rt.assigned_to
            WHERE TRUE
            """;
    private static final String AI_SEARCH_FROM = """
            FROM ai_review_task ai
            JOIN review_task rt ON rt.image_id=ai.image_id
            JOIN image_asset ia ON ia.id=rt.image_id
            LEFT JOIN app_user reviewer ON reviewer.id=rt.assigned_to
            WHERE TRUE
            """;
    private static final String DETAILS_FROM = """
            FROM image_asset ia
            LEFT JOIN review_task rt ON rt.image_id = ia.id
            LEFT JOIN app_user reviewer ON reviewer.id = rt.assigned_to
            WHERE TRUE
            """;
    private static final String VALID_CLOUD = """
            ia.cloud_object_key IS NOT NULL
            AND BTRIM(ia.cloud_object_key) <> ''
            AND ia.cloud_uploaded_at IS NOT NULL
            AND ia.cloud_uploaded_at > :cloudCutoff
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminScreenshotPage search(AdminScreenshotFilters filters, Instant cloudCutoff) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("fetchLimit", filters.limit() + 1)
                .addValue("cloudCutoff", Timestamp.from(cloudCutoff));
        boolean aiOrdered = AiResultFilterSql.completedOnly(filters.aiResult(), filters.aiVerdict(),
                filters.confidenceFrom(), filters.confidenceTo());
        String order = aiOrdered ? "ai" : "rt";
        String baseConditions = conditions(filters, parameters, aiOrdered);
        String listConditions = baseConditions + cursorCondition(filters, parameters, order);
        List<AdminScreenshotListItem> items = jdbc.query("""
                SELECT ia.id, ia.file_name, rt.file_created_at, ia.game_code, ia.session_id,
                       CASE WHEN rt.status = 'COMPLETED' THEN 'CHECKED' ELSE 'UNCHECKED' END
                           AS review_state,
                       CASE
                           WHEN ia.file_available = TRUE AND (%s) THEN 'BOTH'
                           WHEN ia.file_available = TRUE THEN 'LOCAL_ONLY'
                           WHEN (%s) THEN 'B2_ONLY'
                           ELSE 'MISSING'
                       END AS storage_state
                %s
                %s
                ORDER BY %s.file_created_at DESC, %s.image_id DESC
                LIMIT :fetchLimit
                """.formatted(VALID_CLOUD, VALID_CLOUD, aiOrdered ? AI_SEARCH_FROM : SEARCH_FROM, listConditions, order, order),
                parameters,
                AdminScreenshotRepository::mapItem
        );

        boolean hasMore = items.size() > filters.limit();
        List<AdminScreenshotListItem> visibleItems = hasMore
                ? List.copyOf(items.subList(0, filters.limit()))
                : items;
        AdminScreenshotListItem last = hasMore
                ? visibleItems.getLast()
                : null;
        return new AdminScreenshotPage(
                visibleItems,
                last == null ? null : last.fileCreatedAt(),
                last == null ? null : last.imageId()
        );
    }

    public AdminScreenshotSummary summary(AdminScreenshotFilters filters, Instant cloudCutoff) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("cloudCutoff", Timestamp.from(cloudCutoff));
        String baseConditions = conditions(filters, parameters);
        String summaryFrom = requiresImageAsset(filters) ? SEARCH_FROM : SUMMARY_FROM;
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total_count,
                       MIN(rt.file_created_at) AS oldest_created_at,
                       MAX(rt.file_created_at) AS newest_created_at
                %s
                %s
                """.formatted(summaryFrom, baseConditions), parameters,
                (resultSet, rowNumber) -> new AdminScreenshotSummary(
                        resultSet.getLong("total_count"),
                        instant(resultSet, "oldest_created_at"),
                        instant(resultSet, "newest_created_at")
                ));
    }

    public Optional<AdminScreenshotDetails> findById(String imageId, Instant cloudCutoff) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("imageId", imageId)
                .addValue("cloudCutoff", Timestamp.from(cloudCutoff));
        List<AdminScreenshotDetails> details = jdbc.query("""
                SELECT ia.id, ia.file_name,
                       COALESCE(rt.file_created_at, ia.file_created_at) AS file_created_at,
                       ia.processed_at,
                       ia.game_code, ia.token_id, ia.session_id,
                       ia.dealer_cards, ia.active_user_cards, ia.inactive_user_cards,
                       ia.payload_raw, ia.buttons_raw, ia.is_notification,
                       ia.has_stand, ia.has_hit, ia.has_double, ia.has_split, ia.has_surrender,
                       ia.recognition_duration_ms, ia.parse_status, ia.cloud_uploaded_at,
                       CASE WHEN rt.status = 'COMPLETED' THEN 'CHECKED' ELSE 'UNCHECKED' END
                           AS review_state,
                       CASE WHEN rt.status = 'COMPLETED' THEN rt.decision END AS decision,
                       CASE WHEN rt.status = 'COMPLETED' THEN reviewer.username END AS reviewed_by,
                       CASE WHEN rt.status = 'COMPLETED' THEN rt.reviewed_at END AS reviewed_at,
                       CASE
                           WHEN ia.file_available = TRUE AND (%s) THEN 'BOTH'
                           WHEN ia.file_available = TRUE THEN 'LOCAL_ONLY'
                           WHEN (%s) THEN 'B2_ONLY'
                           ELSE 'MISSING'
                       END AS storage_state
                %s
                AND ia.id = :imageId
                """.formatted(VALID_CLOUD, VALID_CLOUD, DETAILS_FROM),
                parameters,
                AdminScreenshotRepository::mapDetails
        );
        return details.stream().findFirst();
    }

    private static String conditions(
            AdminScreenshotFilters filters,
            MapSqlParameterSource parameters
    ) {
        return conditions(filters, parameters, false);
    }

    private static String conditions(AdminScreenshotFilters filters, MapSqlParameterSource parameters, boolean aiOrdered) {
        String queue = aiOrdered ? "ai" : "rt";
        StringBuilder sql = new StringBuilder();
        if (filters.createdFrom() != null) {
            sql.append(" AND ").append(queue).append(".file_created_at >= :createdFrom");
            parameters.addValue("createdFrom", Timestamp.from(filters.createdFrom()));
        }
        if (filters.createdTo() != null) {
            sql.append(" AND ").append(queue).append(".file_created_at < :createdTo");
            parameters.addValue("createdTo", Timestamp.from(filters.createdTo()));
        }
        if (filters.reviewState() == AdminReviewState.CHECKED) {
            sql.append(" AND rt.status = 'COMPLETED'");
        } else if (filters.reviewState() == AdminReviewState.UNCHECKED) {
            sql.append(" AND rt.status <> 'COMPLETED'");
        }
        if (hasText(filters.gameCode())) {
            sql.append(" AND rt.game_code = :gameCode");
            parameters.addValue("gameCode", filters.gameCode().trim());
        }
        if (filters.tokenId() != null) {
            sql.append(" AND ia.token_id = :tokenId");
            parameters.addValue("tokenId", filters.tokenId());
        }
        if (hasText(filters.sessionId())) {
            sql.append(" AND ia.session_id = :sessionId");
            parameters.addValue("sessionId", filters.sessionId().trim());
        }
        if (hasText(filters.imageId())) {
            sql.append(" AND ").append(queue).append(".image_id = :imageId");
            parameters.addValue("imageId", filters.imageId().trim());
        }
        if (hasText(filters.fileName())) {
            sql.append(" AND ia.file_name = :fileName");
            parameters.addValue("fileName", filters.fileName().trim());
        }
        if (filters.decision() != null) {
            sql.append(" AND rt.status = 'COMPLETED' AND rt.decision = :decision");
            parameters.addValue("decision", filters.decision().name());
        }
        if (hasText(filters.reviewedBy())) {
            sql.append(" AND rt.status = 'COMPLETED' AND LOWER(reviewer.username) = LOWER(:reviewedBy)");
            parameters.addValue("reviewedBy", filters.reviewedBy().trim());
        }
        appendStorageFilter(sql, filters.storageState());
        if (filters.parseStatus() != null) {
            sql.append(" AND ia.parse_status = :parseStatus");
            parameters.addValue("parseStatus", filters.parseStatus().name());
        }
        if (filters.notification() != null) {
            sql.append(" AND rt.is_notification = :notification");
            parameters.addValue("notification", filters.notification());
        }
        if (filters.hasUserHand() != null) {
            sql.append(" AND rt.has_user_hand = :hasUserHand");
            parameters.addValue("hasUserHand", filters.hasUserHand());
        }
        AiResultFilterSql.append(sql, parameters, filters.aiResult(), filters.aiVerdict(),
                filters.confidenceFrom(), filters.confidenceTo(), aiOrdered);
        return sql.toString();
    }

    private static boolean requiresImageAsset(AdminScreenshotFilters filters) {
        return filters.tokenId() != null
                || hasText(filters.sessionId())
                || hasText(filters.fileName())
                || filters.storageState() != null
                || filters.parseStatus() != null;
    }

    private static String cursorCondition(
            AdminScreenshotFilters filters,
            MapSqlParameterSource parameters, String queue
    ) {
        if (filters.cursorCreatedAt() == null || !hasText(filters.cursorId())) {
            return "";
        }
        parameters.addValue("cursorCreatedAt", Timestamp.from(filters.cursorCreatedAt()));
        parameters.addValue("cursorId", filters.cursorId().trim());
        return """
                 AND (%s.file_created_at, %s.image_id) < (:cursorCreatedAt, :cursorId)
                """.formatted(queue, queue);
    }

    private static void appendStorageFilter(StringBuilder sql, ImageStorageState storageState) {
        if (storageState == null) {
            return;
        }
        switch (storageState) {
            case LOCAL_ONLY -> sql.append(" AND ia.file_available = TRUE AND NOT (")
                    .append(VALID_CLOUD).append(')');
            case B2_ONLY -> sql.append(" AND ia.file_available = FALSE AND (")
                    .append(VALID_CLOUD).append(')');
            case BOTH -> sql.append(" AND ia.file_available = TRUE AND (")
                    .append(VALID_CLOUD).append(')');
            case MISSING -> sql.append(" AND ia.file_available = FALSE AND NOT (")
                    .append(VALID_CLOUD).append(')');
        }
    }

    private static AdminScreenshotListItem mapItem(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AdminScreenshotListItem(
                resultSet.getString("id"),
                resultSet.getString("file_name"),
                resultSet.getTimestamp("file_created_at").toInstant(),
                resultSet.getString("game_code"),
                resultSet.getString("session_id"),
                AdminReviewState.valueOf(resultSet.getString("review_state")),
                ImageStorageState.valueOf(resultSet.getString("storage_state"))
        );
    }

    private static AdminScreenshotDetails mapDetails(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String decision = resultSet.getString("decision");
        return new AdminScreenshotDetails(
                resultSet.getString("id"),
                resultSet.getString("file_name"),
                resultSet.getTimestamp("file_created_at").toInstant(),
                instant(resultSet, "processed_at"),
                resultSet.getString("game_code"),
                resultSet.getObject("token_id", Long.class),
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
                resultSet.getObject("recognition_duration_ms", Long.class),
                ParseStatus.valueOf(resultSet.getString("parse_status")),
                AdminReviewState.valueOf(resultSet.getString("review_state")),
                decision == null ? null : Decision.valueOf(decision),
                resultSet.getString("reviewed_by"),
                instant(resultSet, "reviewed_at"),
                ImageStorageState.valueOf(resultSet.getString("storage_state")),
                instant(resultSet, "cloud_uploaded_at")
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
