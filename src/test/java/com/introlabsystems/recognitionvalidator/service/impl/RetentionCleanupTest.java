package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.scheduler.RetentionCleanupService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionCleanupTest extends AbstractReviewIntegrationTest {

    @Test
    void usesStartOfCurrentUtcDateAsBoundaryAndLeavesPhysicalFiles() throws Exception {
        Instant now = Instant.parse("2026-08-03T02:00:00Z");
        Instant cutoff = Instant.parse("2026-07-30T00:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.ofHours(-7));
        RetentionCleanupService service =
                new RetentionCleanupService(namedJdbc, properties, fixedClock);
        String oldImageId = insertImage(
                80,
                cutoff.minusSeconds(1),
                "bj_igt",
                "old-session",
                false,
                true
        );
        String boundaryImageId = insertImage(
                81,
                cutoff,
                "bj_igt",
                "boundary-session",
                false,
                true
        );
        Path physicalFile = temporaryDirectory.resolve(oldImageId + ".png");
        Files.writeString(physicalFile, "not touched by retention");

        int deleted = service.runOnce();

        assertThat(deleted).isEqualTo(1);
        assertThat(rowCount("image_asset", oldImageId)).isZero();
        assertThat(rowCount("review_task", oldImageId)).isZero();
        assertThat(rowCount("image_asset", boundaryImageId)).isOne();
        assertThat(rowCount("review_task", boundaryImageId)).isOne();
        assertThat(physicalFile).exists();
    }

    @Test
    void deletesEveryReviewStatusAndKeepsDailyStatistics() {
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        Instant cutoff = Instant.parse("2026-07-30T00:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        RetentionCleanupService service =
                new RetentionCleanupService(namedJdbc, properties, fixedClock);
        UUID operatorId = insertOperator("retention-statuses");
        String pendingImageId = insertImage(
                82, cutoff.minusSeconds(1), "bj_igt", "pending", false, true
        );
        String assignedImageId = insertImage(
                83, cutoff.minusSeconds(2), "bj_igt", "assigned", false, true
        );
        String completedImageId = insertImage(
                84, cutoff.minusSeconds(3), "bj_igt", "completed", false, true
        );
        jdbc.update("""
                UPDATE review_task
                SET status = 'ASSIGNED',
                    assigned_to = ?,
                    assigned_at = ?,
                    lease_expires_at = ?
                WHERE image_id = ?
                """,
                operatorId,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(1800)),
                assignedImageId
        );
        completeReview(
                completedImageId,
                operatorId,
                Decision.ACCEPTED,
                now.minusSeconds(30)
        );
        insertDailyStatistics(operatorId, "2026-08-03", 3, 2, 1);

        int deleted = service.runOnce();

        assertThat(deleted).isEqualTo(3);
        for (String imageId : List.of(
                pendingImageId,
                assignedImageId,
                completedImageId
        )) {
            assertThat(rowCount("image_asset", imageId)).isZero();
            assertThat(rowCount("review_task", imageId)).isZero();
        }
        assertThat(jdbc.queryForMap("""
                SELECT total_checked, matched_count, not_matched_count
                FROM operator_daily_statistics
                WHERE operator_id = ? AND statistics_date = DATE '2026-08-03'
                """, operatorId))
                .containsEntry("total_checked", 3L)
                .containsEntry("matched_count", 2L)
                .containsEntry("not_matched_count", 1L);
    }

    @Test
    void deletesOldRowsInShortDatabaseBatches() {
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        Instant cutoff = Instant.parse("2026-07-30T00:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        RetentionCleanupService service =
                new RetentionCleanupService(namedJdbc, properties, fixedClock);
        for (int index = 0; index < 3; index++) {
            insertImage(
                    90 + index,
                    cutoff.minusSeconds(index + 1L),
                    "bj_igt",
                    "batched-retention-" + index,
                    false,
                    true
            );
        }
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_large_image_asset_delete()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF (SELECT COUNT(*) FROM deleted_image_assets) > 2 THEN
                        RAISE EXCEPTION 'delete batch is too large';
                    END IF;
                    RETURN NULL;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_large_image_asset_delete_trigger
                AFTER DELETE ON image_asset
                REFERENCING OLD TABLE AS deleted_image_assets
                FOR EACH STATEMENT
                EXECUTE FUNCTION reject_large_image_asset_delete()
                """);

        try {
            assertThat(service.runOnce()).isEqualTo(3);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_large_image_asset_delete_trigger ON image_asset");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_large_image_asset_delete()");
        }
    }

    @Test
    void limitsBatchesPerRunToProtectInteractiveTraffic() {
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        Instant cutoff = Instant.parse("2026-07-30T00:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        RetentionCleanupService service =
                new RetentionCleanupService(namedJdbc, properties, fixedClock);
        for (int index = 0; index < 5; index++) {
            insertImage(
                    100 + index,
                    cutoff.minusSeconds(index + 1L),
                    "bj_igt",
                    "bounded-retention-" + index,
                    false,
                    true
            );
        }

        int deleted = service.runOnce();

        assertThat(deleted).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM image_asset WHERE file_created_at < ?",
                Long.class,
                Timestamp.from(cutoff)
        )).isOne();
    }

    @Test
    void deletesAllEligibleRowsWhenBatchLimitIsZero() {
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        Instant cutoff = Instant.parse("2026-07-30T00:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        RetentionCleanupService service = new RetentionCleanupService(
                namedJdbc,
                propertiesWithCleanupMaxBatches(0),
                fixedClock
        );
        for (int index = 0; index < 5; index++) {
            insertImage(
                    110 + index,
                    cutoff.minusSeconds(index + 1L),
                    "bj_igt",
                    "unlimited-retention-" + index,
                    false,
                    true
            );
        }

        int deleted = service.runOnce();

        assertThat(deleted).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM image_asset WHERE file_created_at < ?",
                Long.class,
                Timestamp.from(cutoff)
        )).isZero();
    }
}
