package com.introlabsystems.recognitionvalidator.maintenance;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ValidatorProperties properties;
    private final Clock clock;

    public RetentionCleanupService(
            NamedParameterJdbcTemplate jdbc,
            ValidatorProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${validator.cleanup-cron}", zone = "UTC")
    public int runOnce() {
        long startedAt = System.nanoTime();
        Instant cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .minusDays(properties.retention().toDays())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        int totalDeleted = 0;
        int batches = 0;
        while (batches < properties.cleanupMaxBatches()) {
            int deleted = deleteBatch(cutoff);
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

    private int deleteBatch(Instant cutoff) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT id
                    FROM image_asset
                    WHERE file_created_at < :cutoff
                    ORDER BY file_created_at, id
                    LIMIT :batchSize
                )
                DELETE FROM image_asset image
                USING expired
                WHERE image.id = expired.id
                """, new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("batchSize", properties.cleanupBatchSize()));
    }
}
