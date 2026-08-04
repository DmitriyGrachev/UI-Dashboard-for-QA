package com.introlabsystems.recognitionvalidator.review;

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
public class RejectedScreenshotExportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RejectedScreenshotExportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExportCandidate> findCandidates(
            Instant createdFrom,
            Instant createdTo,
            boolean includePreviouslyDownloaded
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT rt.image_id
                FROM review_task rt
                JOIN image_asset ia ON ia.id = rt.image_id
                WHERE rt.status = 'COMPLETED'
                  AND rt.decision = 'REJECTED'
                  AND ia.file_available = TRUE
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (!includePreviouslyDownloaded) {
            sql.append(" AND rt.rejected_downloaded_at IS NULL");
        }
        if (createdFrom != null) {
            sql.append(" AND ia.file_created_at >= :createdFrom");
            parameters.addValue("createdFrom", Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            sql.append(" AND ia.file_created_at < :createdTo");
            parameters.addValue("createdTo", Timestamp.from(createdTo));
        }
        sql.append(" ORDER BY ia.file_created_at, rt.image_id");

        return jdbc.query(
                sql.toString(),
                parameters,
                (resultSet, rowNumber) -> new ExportCandidate(resultSet.getString("image_id"))
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

    public record ExportCandidate(String imageId) {
    }
}
