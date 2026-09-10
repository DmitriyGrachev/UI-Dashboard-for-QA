package com.introlabsystems.recognitionvalidator.dao.jdbc;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class RejectedScreenshotExportRepository {

    public static final String AVAILABLE_IMAGE_PREDICATE = """
            (
                ia.file_available = TRUE
                OR (
                    ia.cloud_object_key IS NOT NULL
                    AND BTRIM(ia.cloud_object_key) <> ''
                    AND ia.cloud_uploaded_at IS NOT NULL
                    AND ia.cloud_uploaded_at > CURRENT_TIMESTAMP - INTERVAL '21 days'
                )
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public List<ExportCandidate> findCandidates(
            Instant processedFrom,
            Instant processedTo,
            boolean includePreviouslyDownloaded,
            String sessionId,
            boolean aiMismatch
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT ia.id AS image_id, ia.session_id, ai.verdict, ai.confidence, ai.message,
                       rt.decision
                FROM image_asset ia
                LEFT JOIN ai_review_task ai ON ai.image_id = ia.id
                LEFT JOIN review_task rt ON rt.image_id = ia.id
                WHERE %s
                """.formatted(AVAILABLE_IMAGE_PREDICATE));
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (aiMismatch) {
            sql.append(" AND ai.status = 'COMPLETED' AND ai.verdict = 'MISMATCH'");
        } else {
            sql.append(" AND rt.status = 'COMPLETED' AND rt.decision = 'REJECTED'");
            if (!includePreviouslyDownloaded) sql.append(" AND rt.rejected_downloaded_at IS NULL");
        }
        if (processedFrom != null) {
            sql.append(" AND ia.processed_at >= :processedFrom");
            parameters.addValue("processedFrom", Timestamp.from(processedFrom));
        }
        if (processedTo != null) {
            sql.append(" AND ia.processed_at < :processedTo");
            parameters.addValue("processedTo", Timestamp.from(processedTo));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND ia.session_id = :sessionId");
            parameters.addValue("sessionId", sessionId.trim());
        }
        sql.append(" ORDER BY ia.processed_at, ia.id");

        return jdbc.query(
                sql.toString(),
                parameters,
                (resultSet, rowNumber) -> new ExportCandidate(resultSet.getString("image_id"),
                        resultSet.getString("session_id"), resultSet.getString("verdict"),
                        resultSet.getObject("confidence", Integer.class), resultSet.getString("message"),
                        resultSet.getString("decision"))
        );
    }

    @Transactional
    public int markDownloaded(Collection<String> imageIds, Instant downloadedAt) {
        if (imageIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                UPDATE review_task
                SET rejected_downloaded_at = :downloadedAt
                WHERE image_id IN (:imageIds)
                  AND rejected_downloaded_at IS NULL
                """, Map.of(
                "downloadedAt", Timestamp.from(downloadedAt),
                "imageIds", imageIds
        ));
    }

    public record ExportCandidate(String imageId, String sessionId, String aiVerdict,
                                  Integer aiConfidence, String aiMessage, String operatorDecision) {
    }
}
