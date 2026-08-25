package com.introlabsystems.recognitionvalidator.dao.jdbc;

import com.introlabsystems.recognitionvalidator.model.value.CloudUploadCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CloudUploadRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CloudUploadRepository repository;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE operator_daily_statistics, review_task, image_asset, app_user CASCADE");
    }

    @Test
    void findsOnlyLocalUnuploadedDueRowsOldestFirstAndRespectsLimit() {
        insertImage("oldest", NOW.minusSeconds(300), true, null, null, 0);
        insertImage("retry-due", NOW.minusSeconds(200), true, null, NOW.minusSeconds(1), 2);
        insertImage("newest-due", NOW.minusSeconds(100), true, null, null, 0);
        insertImage("retry-later", NOW.minusSeconds(50), true, null, NOW.plusSeconds(60), 1);
        insertImage("already-uploaded", NOW.minusSeconds(400), true, "validator/already-uploaded.png", null, 0);
        insertImage("local-missing", NOW.minusSeconds(500), false, null, null, 0);

        List<CloudUploadCandidate> candidates = repository.findCandidates(NOW, 3);

        assertThat(candidates)
                .extracting(CloudUploadCandidate::imageId)
                .containsExactly("oldest", "retry-due", "newest-due");
        assertThat(candidates)
                .extracting(CloudUploadCandidate::relativePath)
                .containsExactly("oldest.png", "retry-due.png", "newest-due.png");
    }

    @Test
    void markUploadedStoresKeyAndTimeClearsRetryAndResetsAttempts() {
        insertImage("upload-me", NOW.minusSeconds(10), true, null, NOW.plusSeconds(300), 4);
        Instant uploadedAt = NOW.plusSeconds(5);

        int updated = repository.markUploaded("upload-me", "validator/upload-me.png", uploadedAt);

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT cloud_object_key, cloud_uploaded_at, cloud_upload_next_attempt_at, "
                        + "cloud_upload_attempt_count FROM image_asset WHERE id = ?",
                "upload-me"
        )).containsEntry("cloud_object_key", "validator/upload-me.png")
                .containsEntry("cloud_uploaded_at", Timestamp.from(uploadedAt))
                .containsEntry("cloud_upload_next_attempt_at", null)
                .containsEntry("cloud_upload_attempt_count", 0);
    }

    @Test
    void staleUploadedUpdateDoesNotOverwriteExistingCloudState() {
        insertImage("already-uploaded", NOW.minusSeconds(10), true,
                "validator/old-key.png", null, 2);
        Instant originalUploadedAt = NOW.minusSeconds(5);

        jdbc.update(
                "UPDATE image_asset SET cloud_uploaded_at = ? WHERE id = ?",
                Timestamp.from(originalUploadedAt), "already-uploaded"
        );

        int updated = repository.markUploaded(
                "already-uploaded", "validator/new-key.png", NOW.plusSeconds(5)
        );

        assertThat(updated).isZero();
        assertThat(jdbc.queryForMap(
                "SELECT cloud_object_key, cloud_uploaded_at, cloud_upload_next_attempt_at, "
                        + "cloud_upload_attempt_count FROM image_asset WHERE id = ?",
                "already-uploaded"
        )).containsEntry("cloud_object_key", "validator/old-key.png")
                .containsEntry("cloud_uploaded_at", Timestamp.from(originalUploadedAt))
                .containsEntry("cloud_upload_next_attempt_at", null)
                .containsEntry("cloud_upload_attempt_count", 2);
    }

    @Test
    void markFailedIncrementsAttemptsAndPostponesSelectionUntilRetryTime() {
        insertImage("retry-me", NOW.minusSeconds(10), true, null, null, 2);
        Instant retryAt = NOW.plusSeconds(300);

        int updated = repository.markFailed("retry-me", retryAt);

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT cloud_upload_next_attempt_at, cloud_upload_attempt_count "
                        + "FROM image_asset WHERE id = ?",
                "retry-me"
        )).containsEntry("cloud_upload_next_attempt_at", Timestamp.from(retryAt))
                .containsEntry("cloud_upload_attempt_count", 3);
        assertThat(repository.findCandidates(NOW, 10)).isEmpty();
        assertThat(repository.findCandidates(retryAt, 10))
                .extracting(CloudUploadCandidate::imageId)
                .containsExactly("retry-me");
    }

    @Test
    void preparedAttemptRemainsRecoverableAfterLocalFileDisappears() {
        insertImage("recover-me", NOW.minusSeconds(10), true, null, null, 0);
        Instant retryAt = NOW.plusSeconds(300);

        int updated = repository.markAttemptStarted(
                "recover-me",
                "validator/recover-me.png",
                retryAt
        );
        jdbc.update("UPDATE image_asset SET file_available = FALSE WHERE id = ?", "recover-me");

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT cloud_object_key, cloud_upload_next_attempt_at, "
                        + "cloud_upload_attempt_count FROM image_asset WHERE id = ?",
                "recover-me"
        )).containsEntry("cloud_object_key", "validator/recover-me.png")
                .containsEntry("cloud_upload_next_attempt_at", Timestamp.from(retryAt))
                .containsEntry("cloud_upload_attempt_count", 1);
        assertThat(repository.findCandidates(NOW, 10)).isEmpty();
        assertThat(repository.findCandidates(retryAt, 10))
                .extracting(CloudUploadCandidate::imageId)
                .containsExactly("recover-me");
    }

    @Test
    void failedUpdateDoesNotClearAnAlreadyUploadedCloudState() {
        insertImage("uploaded", NOW.minusSeconds(10), true,
                "validator/uploaded.png", null, 2);
        Instant nextAttemptAt = NOW.plusSeconds(300);

        int updated = repository.markFailed("uploaded", nextAttemptAt);

        assertThat(updated).isZero();
        assertThat(jdbc.queryForMap(
                "SELECT cloud_object_key, cloud_uploaded_at, cloud_upload_next_attempt_at, "
                        + "cloud_upload_attempt_count FROM image_asset WHERE id = ?",
                "uploaded"
        )).containsEntry("cloud_object_key", "validator/uploaded.png")
                .containsEntry("cloud_uploaded_at", Timestamp.from(NOW.minusSeconds(10)))
                .containsEntry("cloud_upload_next_attempt_at", null)
                .containsEntry("cloud_upload_attempt_count", 2);
    }

    private void insertImage(
            String id,
            Instant createdAt,
            boolean fileAvailable,
            String objectKey,
            Instant nextAttemptAt,
            int attemptCount
    ) {
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code,
                    is_notification, has_stand, has_hit, has_double, has_split,
                    parse_status, cloud_object_key, cloud_uploaded_at,
                    cloud_upload_next_attempt_at, cloud_upload_attempt_count
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, 'bj_igt', FALSE, FALSE, FALSE, FALSE, FALSE,
                    'SUCCESS', ?, ?, ?, ?
                )
                """,
                id,
                id + ".png",
                id + ".png",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                fileAvailable,
                objectKey,
                objectKey == null ? null : Timestamp.from(createdAt),
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                attemptCount
        );
    }
}
