package com.introlabsystems.recognitionvalidator.scheduler;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ValidatorProperties properties;
    private final Duration cloudMetadataRetention;
    private final Clock clock;

    public RetentionCleanupService(
            NamedParameterJdbcTemplate jdbc,
            ValidatorProperties properties,
            B2StorageProperties b2Properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cloudMetadataRetention = b2Properties.metadataRetention();
        this.clock = clock;
    }

    @Scheduled(cron = "${validator.cleanup-cron}", zone = "UTC")
    public int runOnce() {
        long startedAt = System.nanoTime();
        Instant cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .minusDays(properties.retention().toDays())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Instant cloudCutoff = clock.instant().minus(cloudMetadataRetention);
        int totalDeleted = 0;
        int batches = 0;
        while (properties.cleanupMaxBatches() == 0
                || batches < properties.cleanupMaxBatches()) {
            int deleted = deleteBatch(cutoff, cloudCutoff);
            if (deleted == 0) {
                break;
            }
            totalDeleted += deleted;
            batches++;
            if (deleted < properties.cleanupBatchSize()) {
                break;
            }
        }
        if (totalDeleted > 0) {
            log.info(
                    "Retention cleanup completed: deleted={}, batches={}, durationMs={}",
                    totalDeleted,
                    batches,
                    (System.nanoTime() - startedAt) / 1_000_000
            );
        }
        return totalDeleted;
    }

    private int deleteBatch(Instant localCutoff, Instant cloudCutoff) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT ia.id
                    FROM image_asset ia
                    WHERE ((
                              cloud_uploaded_at IS NULL
                              AND file_created_at < :localCutoff
                          )
                       OR (
                              cloud_uploaded_at IS NOT NULL
                              AND cloud_uploaded_at < :cloudCutoff
                          ))
                      AND NOT EXISTS (
                          SELECT 1 FROM ai_review_task ai WHERE ai.image_id = ia.id
                            AND ai.status = 'PROCESSING' AND ai.lease_expires_at > clock_timestamp()
                      )
                    ORDER BY ia.file_created_at, ia.id
                    LIMIT :batchSize
                    FOR UPDATE OF ia SKIP LOCKED
                ), ai_deletable AS (
                    SELECT ai.image_id FROM ai_review_task ai JOIN expired e ON e.id = ai.image_id
                    WHERE ai.status <> 'PROCESSING' OR ai.lease_expires_at IS NULL
                       OR ai.lease_expires_at <= clock_timestamp()
                    FOR UPDATE OF ai SKIP LOCKED
                )
                DELETE FROM image_asset image
                USING expired
                WHERE image.id = expired.id
                  AND (NOT EXISTS (SELECT 1 FROM ai_review_task ai WHERE ai.image_id = expired.id)
                       OR EXISTS (SELECT 1 FROM ai_deletable d WHERE d.image_id = expired.id))
                """, new MapSqlParameterSource()
                .addValue("localCutoff", Timestamp.from(localCutoff))
                .addValue("cloudCutoff", Timestamp.from(cloudCutoff))
                .addValue("batchSize", properties.cleanupBatchSize()));
    }
}
