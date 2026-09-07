package com.introlabsystems.recognitionvalidator.dao.jdbc;

import com.introlabsystems.recognitionvalidator.model.value.StorageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class StorageStatusRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StorageStatus find(boolean enabled, Instant now, Duration metadataRetention) {
        return observation(enabled, now, metadataRetention).status();
    }

    public Observation observation(boolean enabled, Instant now, Duration metadataRetention) {
        Instant cloudCutoff = now.minus(metadataRetention);
        return jdbc.queryForObject("""
                WITH base AS (
                    SELECT
                        file_available = TRUE AS local_available,
                        cloud_object_key IS NOT NULL
                            AND BTRIM(cloud_object_key) <> ''
                            AND cloud_uploaded_at IS NOT NULL
                            AND cloud_uploaded_at > :cloudCutoff AS valid_cloud,
                        cloud_uploaded_at IS NULL
                            AND (
                                file_available = TRUE
                                OR (
                                    cloud_object_key IS NOT NULL
                                    AND cloud_upload_attempt_count > 0
                                )
                            ) AS backlog,
                        cloud_upload_attempt_count,
                        cloud_uploaded_at,
                        cloud_upload_next_attempt_at,
                        file_created_at
                    FROM image_asset
                ), classified AS (
                    SELECT
                        *,
                        backlog
                            AND (
                                cloud_upload_next_attempt_at IS NULL
                                OR cloud_upload_next_attempt_at <= :now
                            ) AS due_now,
                        backlog
                            AND cloud_upload_next_attempt_at > :now AS retrying
                    FROM base
                )
                SELECT
                    COUNT(*) FILTER (WHERE valid_cloud) AS uploaded,
                    COUNT(*) FILTER (WHERE backlog) AS backlog,
                    COUNT(*) FILTER (WHERE due_now) AS due_now,
                    COUNT(*) FILTER (WHERE retrying) AS retrying,
                    COUNT(*) FILTER (WHERE local_available AND NOT valid_cloud) AS local_only,
                    COUNT(*) FILTER (WHERE NOT local_available AND valid_cloud) AS cloud_only,
                    COUNT(*) FILTER (WHERE local_available AND valid_cloud) AS both_stores,
                    COUNT(*) FILTER (WHERE NOT local_available AND NOT valid_cloud AND NOT backlog) AS unavailable,
                    MIN(file_created_at) FILTER (WHERE backlog) AS oldest_pending_at,
                    COUNT(*) FILTER (WHERE backlog AND cloud_upload_attempt_count >= 2) AS repeated_attempts,
                    MAX(cloud_uploaded_at) AS last_upload
                FROM classified
                """,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("cloudCutoff", Timestamp.from(cloudCutoff)),
                (resultSet, rowNumber) -> {
                    Timestamp oldestPendingAt = resultSet.getTimestamp("oldest_pending_at");
                    StorageStatus status = new StorageStatus(
                            enabled,
                            resultSet.getLong("uploaded"),
                            resultSet.getLong("backlog"),
                            resultSet.getLong("due_now"),
                            resultSet.getLong("retrying"),
                            resultSet.getLong("local_only"),
                            resultSet.getLong("cloud_only"),
                            resultSet.getLong("both_stores"),
                            resultSet.getLong("unavailable"),
                            oldestPendingAt == null ? null : oldestPendingAt.toInstant()
                    );
                    Timestamp lastUpload = resultSet.getTimestamp("last_upload");
                    return new Observation(
                            status,
                            resultSet.getLong("repeated_attempts"),
                            lastUpload == null ? null : lastUpload.toInstant()
                    );
                }
        );
    }

    public record Observation(StorageStatus status, long repeatedAttempts, Instant lastUpload) {
    }
}
