package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@SpringBootTest(properties = "validator.retention=4d")
@ActiveProfiles("test")
abstract class AbstractReviewIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    protected ValidatorProperties properties;

    @TempDir
    protected Path temporaryDirectory;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE operator_daily_statistics, review_task, image_asset, app_user CASCADE");
    }

    protected Boolean tableExists(String tableName) {
        return jdbc.queryForObject(
                "select to_regclass('public." + tableName + "') is not null",
                Boolean.class
        );
    }

    protected UUID insertOperator(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at)
                VALUES (?, ?, 'hash', TRUE, now())
                """, id, username);
        return id;
    }

    protected String insertImage(
            int number,
            Instant createdAt,
            String gameCode,
            String sessionId,
            boolean notification,
            boolean available
    ) {
        String id = "%064x".formatted(number);
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code, session_id,
                    is_notification, has_stand, has_hit, has_double, has_split, parse_status
                ) VALUES (
                    ?, ?, ?, ?, ?, now(), now(), ?, ?, ?,
                    ?, FALSE, FALSE, FALSE, FALSE, 'SUCCESS'
                )
                """,
                id,
                id + ".png",
                id + ".png",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                available,
                gameCode,
                sessionId,
                notification
        );
        jdbc.update(
                "INSERT INTO review_task (image_id, status) VALUES (?, 'PENDING')",
                id
        );
        return id;
    }

    protected void completeReview(
            String imageId,
            UUID operatorId,
            Decision decision,
            Instant reviewedAt
    ) {
        jdbc.update("""
                UPDATE review_task
                SET status = 'COMPLETED',
                    assigned_to = ?,
                    assigned_at = ?,
                    decision = ?,
                    reviewed_at = ?
                WHERE image_id = ?
                """,
                operatorId,
                Timestamp.from(reviewedAt.minusSeconds(30)),
                decision.name(),
                Timestamp.from(reviewedAt),
                imageId
        );
    }

    protected void insertDailyStatistics(
            UUID operatorId,
            String date,
            long total,
            long matched,
            long notMatched
    ) {
        jdbc.update("""
                INSERT INTO operator_daily_statistics (
                    operator_id, statistics_date, total_checked,
                    matched_count, not_matched_count
                ) VALUES (?, CAST(? AS date), ?, ?, ?)
                """, operatorId, date, total, matched, notMatched);
    }

    protected long rowCount(String tableName, String imageId) {
        String idColumn = "image_asset".equals(tableName) ? "id" : "image_id";
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + tableName + " WHERE " + idColumn + " = ?",
                Long.class,
                imageId
        );
    }

    protected ValidatorProperties propertiesWithCleanupMaxBatches(int cleanupMaxBatches) {
        return new ValidatorProperties(
                properties.imageRoot(),
                properties.games(),
                properties.batchSize(),
                properties.leaseDuration(),
                properties.retention(),
                properties.cleanupBatchSize(),
                cleanupMaxBatches,
                properties.watchEnabled(),
                properties.watchFlushInterval(),
                properties.watchMaxPendingEvents(),
                properties.countRemainingScreenshots()
        );
    }
}
