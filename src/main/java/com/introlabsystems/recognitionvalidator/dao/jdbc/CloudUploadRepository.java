package com.introlabsystems.recognitionvalidator.dao.jdbc;

import com.introlabsystems.recognitionvalidator.model.value.CloudUploadCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class CloudUploadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public List<CloudUploadCandidate> findCandidates(Instant now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id,
                       relative_path,
                       file_created_at,
                       cloud_object_key,
                       cloud_upload_next_attempt_at,
                       cloud_upload_attempt_count
                FROM image_asset
                WHERE cloud_uploaded_at IS NULL
                  AND (
                      file_available = TRUE
                      OR (
                          cloud_object_key IS NOT NULL
                          AND cloud_upload_attempt_count > 0
                      )
                  )
                  AND (
                      cloud_upload_next_attempt_at IS NULL
                      OR cloud_upload_next_attempt_at <= :now
                  )
                ORDER BY file_created_at ASC, id ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> new CloudUploadCandidate(
                        resultSet.getString("id"),
                        resultSet.getString("relative_path"),
                        resultSet.getTimestamp("file_created_at").toInstant(),
                        resultSet.getString("cloud_object_key"),
                        resultSet.getTimestamp("cloud_upload_next_attempt_at") == null
                                ? null
                                : resultSet.getTimestamp("cloud_upload_next_attempt_at").toInstant(),
                        resultSet.getInt("cloud_upload_attempt_count")
                )
        );
    }

    public int markAttemptStarted(String imageId, String objectKey, Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE image_asset
                SET cloud_object_key = :objectKey,
                    cloud_upload_next_attempt_at = :nextAttemptAt,
                    cloud_upload_attempt_count = cloud_upload_attempt_count + 1
                WHERE id = :imageId
                  AND cloud_uploaded_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("imageId", imageId)
                        .addValue("objectKey", objectKey)
                        .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
        );
    }

    public int markUploaded(String imageId, String objectKey, Instant uploadedAt) {
        return jdbc.update("""
                UPDATE image_asset
                SET cloud_object_key = :objectKey,
                    cloud_uploaded_at = :uploadedAt,
                    cloud_upload_next_attempt_at = NULL,
                    cloud_upload_attempt_count = 0
                WHERE id = :imageId
                  AND cloud_uploaded_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("imageId", imageId)
                        .addValue("objectKey", objectKey)
                        .addValue("uploadedAt", Timestamp.from(uploadedAt))
        );
    }

    public int clearAttempt(String imageId) {
        return jdbc.update("""
                UPDATE image_asset
                SET cloud_object_key = NULL,
                    cloud_upload_next_attempt_at = NULL,
                    cloud_upload_attempt_count = 0
                WHERE id = :imageId
                  AND cloud_uploaded_at IS NULL
                """,
                new MapSqlParameterSource("imageId", imageId)
        );
    }

    public int markFailed(String imageId, Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE image_asset
                SET cloud_upload_next_attempt_at = :nextAttemptAt,
                    cloud_upload_attempt_count = cloud_upload_attempt_count + 1
                WHERE id = :imageId
                  AND cloud_uploaded_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("imageId", imageId)
                        .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
        );
    }
}
