package com.introlabsystems.recognitionvalidator.slack;

import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "slack.enabled=false"
})
class SlackOperationsDataTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SlackOperationsRepository operations;

    @Autowired
    private DailyStatisticsRepository dailyStatistics;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE operator_daily_statistics, review_task, image_asset, app_user, "
                + "ai_selection_rule, ai_queue_settings, ai_daily_statistics, review_disagreement CASCADE");
        jdbc.update("INSERT INTO ai_queue_settings(id, revision, enabled) VALUES (1, 0, FALSE)");
    }

    @Test
    void snapshotCountsEligiblePendingOnceAndKeepsExpiredLeasesVisible() {
        String eligible = insertAiImage(1, "PENDING", true, 11L, "session-a", null, null, null);
        insertAiImage(2, "PENDING", true, 12L, "session-a", null, null, null);
        insertAiImage(3, "COMPLETED", true, 11L, "session-a", NOW.minusSeconds(30), true, null);
        insertAiImage(4, "PROCESSING", true, 11L, "session-a", null, null, NOW.plusSeconds(30));
        insertAiImage(5, "PROCESSING", true, 11L, "session-a", null, null, NOW.minusSeconds(30));
        insertAiImage(6, "PENDING", false, 11L, "session-a", null, null, null);
        insertAiImage(7, "PENDING", true, 12L, "session-a", null, null, null);

        jdbc.update("UPDATE ai_queue_settings SET enabled = TRUE WHERE id = 1");
        insertRule("rule-a", 0, 11L, "session-a");
        insertRule("rule-overlap", 1, 11L, "session-a");

        jdbc.update("UPDATE image_asset SET cloud_object_key = 'validator/id6.png', cloud_uploaded_at = ?, "
                + "cloud_upload_attempt_count = 0 WHERE id = ?", Timestamp.from(NOW), id(6));
        jdbc.update("UPDATE image_asset SET cloud_object_key = 'validator/id7.png', "
                + "cloud_upload_attempt_count = 2 WHERE id = ?", id(7));
        dailyStatistics.rebuildAiFromCompletedTasks();

        SlackOperationsRepository.Metrics metrics = operations.snapshot(NOW);

        assertThat(eligible).isEqualTo(id(1));
        assertThat(metrics.aiEnabled()).isTrue();
        assertThat(metrics.aiEligiblePending()).isOne();
        assertThat(metrics.aiProcessing()).isEqualTo(2);
        assertThat(metrics.aiExpired()).isOne();
        assertThat(metrics.aiLastResult()).isEqualTo(NOW.minusSeconds(30));
        assertThat(metrics.b2().enabled()).isFalse();
        assertThat(metrics.b2().backlog()).isEqualTo(6);
        assertThat(metrics.b2().repeatedAttempts()).isOne();
        assertThat(metrics.b2().lastUpload()).isEqualTo(NOW);
    }

    @Test
    void dailyUsesUtcHalfOpenBoundariesAndSeparatesAiVerdicts() {
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, enabled, created_at, role) "
                + "VALUES (?, 'operator', 'unused', TRUE, ?, 'OPERATOR')", operator, Timestamp.from(NOW));
        LocalDate day = LocalDate.of(2026, 9, 6);
        jdbc.update("INSERT INTO operator_daily_statistics(operator_id, statistics_date, total_checked, "
                + "matched_count, not_matched_count) VALUES (?, ?, 5, 3, 2)", operator, day);
        jdbc.update("INSERT INTO operator_daily_statistics(operator_id, statistics_date, total_checked, "
                + "matched_count, not_matched_count) VALUES (?, ?, 99, 99, 0)", operator, day.minusDays(1));

        insertAiImage(10, "COMPLETED", true, null, null,
                day.atStartOfDay().toInstant(ZoneOffset.UTC), true, null);
        insertAiImage(11, "COMPLETED", true, null, null,
                day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(1), false, null);
        insertAiImage(12, "COMPLETED", true, null, null,
                day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), true, null);
        dailyStatistics.rebuildAiFromCompletedTasks();

        SlackOperationsRepository.Daily daily = operations.daily(day);

        assertThat(daily.operatorChecked()).isEqualTo(5);
        assertThat(daily.operatorRejected()).isEqualTo(2);
        assertThat(daily.aiChecked()).isEqualTo(2);
        assertThat(daily.aiMatch()).isOne();
        assertThat(daily.aiRejected()).isOne();
    }

    @Test
    void dailyDisagreementsUseSecondReviewDayAndKeepDirectionsSeparate() {
        LocalDate day = LocalDate.of(2026, 9, 6);
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (int i = 0; i < 4; i++) {
            Instant at = switch (i) {
                case 0 -> start.minusNanos(1000);
                case 1 -> start;
                case 2 -> start.plusSeconds(86400).minusNanos(1000);
                default -> start.plusSeconds(86400);
            };
            jdbc.update("INSERT INTO review_disagreement(image_id, observed_at, ai_matched) VALUES (?, ?, ?)",
                    id(90 + i), Timestamp.from(at), i != 2);
        }
        var daily = operations.daily(day);
        assertThat(daily.aiMatchedOperatorRejected()).isOne();
        assertThat(daily.aiUnmatchedOperatorAccepted()).isOne();
        assertThat(operations.daily(day.plusDays(2)).aiMatchedOperatorRejected()).isZero();
    }

    @Test
    void pendingRejectsUsesExportableBacklogCount() {
        String available = insertReviewImage(20, true);
        String unavailable = insertReviewImage(21, false);
        jdbc.update("UPDATE review_task SET status='COMPLETED', decision='REJECTED' WHERE image_id IN (?, ?)",
                available, unavailable);

        assertThat(operations.pendingRejects()).isOne();
    }

    private String insertAiImage(
            int number,
            String status,
            boolean available,
            Long token,
            String session,
            Instant checkedAt,
            Boolean valid,
            Instant leaseExpiresAt
    ) {
        String imageId = id(number);
        Instant createdAt = NOW.minusSeconds(number);
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code, token_id,
                    session_id, is_notification, has_stand, has_hit, has_double, has_split,
                    parse_status, cloud_upload_attempt_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'bj_single_deck_ags', ?, ?, FALSE, FALSE, FALSE, FALSE, FALSE,
                    'SUCCESS', 0)
                """, imageId, imageId + ".png", imageId + ".png", Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt), available,
                token, session);
        jdbc.update("""
                INSERT INTO review_task (
                    image_id, status, file_created_at, game_code, token_id, session_id,
                    is_notification, has_user_hand
                ) VALUES (?, 'PENDING', ?, 'bj_single_deck_ags', ?, ?, FALSE, TRUE)
                """, imageId, Timestamp.from(createdAt), token, session);
        jdbc.update("""
                INSERT INTO ai_review_task (
                    image_id, status, file_created_at, game_code, token_id, session_id,
                    is_notification, has_user_hand, attempt_count, file_available,
                    checked_at, valid, verdict, lease_expires_at
                ) VALUES (?, ?, ?, 'bj_single_deck_ags', ?, ?, FALSE, TRUE, 0, ?, ?, ?, ?, ?)
                """, imageId, status, Timestamp.from(createdAt), token, session, available,
                timestamp(checkedAt), valid, valid == null ? null : (valid ? "MATCH" : "MISMATCH"),
                timestamp(leaseExpiresAt));
        return imageId;
    }

    private String insertReviewImage(int number, boolean available) {
        String imageId = id(number);
        Instant createdAt = NOW.minusSeconds(number);
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code, session_id,
                    is_notification, has_stand, has_hit, has_double, has_split, parse_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'bj_igt', ?, FALSE, TRUE, FALSE, FALSE, FALSE, 'SUCCESS')
                """, imageId, imageId + ".png", imageId + ".png", Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt), available,
                "export-session-" + number);
        jdbc.update("""
                INSERT INTO review_task (
                    image_id, status, file_created_at, game_code, session_id, is_notification, has_user_hand
                ) VALUES (?, 'PENDING', ?, 'bj_igt', ?, FALSE, TRUE)
                """, imageId, Timestamp.from(createdAt), "export-session-" + number);
        return imageId;
    }

    private void insertRule(String name, int priority, Long token, String session) {
        jdbc.update("""
                INSERT INTO ai_selection_rule(id, name, enabled, priority, token_id, session_id)
                VALUES (?, ?, TRUE, ?, ?, ?)
                """, UUID.randomUUID(), name, priority, token, session);
    }

    private static String id(int number) {
        return "%064x".formatted(number);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
