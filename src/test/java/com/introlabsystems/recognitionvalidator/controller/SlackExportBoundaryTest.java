package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.slack.SlackNotificationOutboxRepository;
import com.introlabsystems.recognitionvalidator.slack.SlackNotificationScheduler;
import com.introlabsystems.recognitionvalidator.slack.SlackProperties;
import com.introlabsystems.recognitionvalidator.slack.SlackWebApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "slack.enabled=true",
        "slack.bot-token=test-token",
        "slack.channel-id=test-channel",
        "slack.rejected-details-limit=10",
        "slack.rejected-archive-url=https://validator.test/rejected"
})
class SlackExportBoundaryTest extends AbstractWebIntegrationTest {

    private static final Instant EXPORTED_AT = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ADMIN = "admin";

    @MockitoBean
    private SlackNotificationScheduler scheduler;

    @MockitoBean
    private SlackWebApiClient slack;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private SlackNotificationOutboxRepository outbox;

    @MockitoSpyBean
    private SlackProperties slackProperties;

    @BeforeEach
    void resetSlackBoundaryState() {
        jdbc.execute("TRUNCATE TABLE slack_notification_outbox, slack_notification_state "
                + "RESTART IDENTITY CASCADE");
        when(clock.instant()).thenReturn(EXPORTED_AT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void partialExportMarksOnlyIncludedImagesAndQueuesOneFixedArchive() throws Exception {
        byte[] includedBytes = {1, 2};
        byte[] excludedBytes = {3, 4};
        String includedId = insertRejectedExportImage(401, "included.png", includedBytes);
        String excludedId = insertRejectedExportImage(402, "excluded.png", excludedBytes);
        jdbc.update("UPDATE image_asset SET processed_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-20T10:00:00Z")), includedId);
        jdbc.update("UPDATE image_asset SET processed_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-21T10:00:00Z")), excludedId);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        rejectedExports.writeZip(
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
                false,
                archive,
                ADMIN
        );

        assertThat(readZipEntries(archive.toByteArray()))
                .containsExactlyEntriesOf(Map.of("included.png", includedBytes));
        assertThat(downloadedAt(includedId)).isEqualTo(EXPORTED_AT);
        assertThat(downloadedAt(excludedId)).isNull();
        assertThat(archiveCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM slack_notification_outbox
                 WHERE operation_kind = 'REFRESH' AND delivery_phase = 'PENDING'
                """, Long.class)).isEqualTo(1L);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT operation_kind, archive_payload, created_at, next_attempt_at
                  FROM slack_notification_outbox
                 WHERE operation_kind = 'ARCHIVE'
                """);
        assertThat(row.get("operation_kind")).isEqualTo("ARCHIVE");
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(EXPORTED_AT));
        assertThat(row.get("next_attempt_at")).isEqualTo(Timestamp.from(EXPORTED_AT));
        assertThat(row.get("archive_payload").toString())
                .contains("*1 file* downloaded by `admin`", "20 Aug 2026, 12:00 UTC");
    }

    @Test
    void rejectAddedAfterCandidateSnapshotIsNotMarkedInThatExport() throws Exception {
        String originalId = insertRejectedExportImage(403, "original.png", new byte[]{1});
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        String[] lateId = new String[1];
        OutputStream output = new OutputStream() {
            private boolean inserted;

            @Override
            public void write(int value) throws IOException {
                insertLateCandidate();
                archive.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                insertLateCandidate();
                archive.write(bytes, offset, length);
            }

            private void insertLateCandidate() throws IOException {
                if (!inserted) {
                    inserted = true;
                    try {
                        lateId[0] = insertRejectedExportImage(404, "late.png", new byte[]{2});
                    } catch (Exception exception) {
                        throw new IOException(exception);
                    }
                }
            }
        };

        int written = rejectedExports.writeZip(null, null, false, output, ADMIN);

        assertThat(written).isEqualTo(1);
        assertThat(readZipEntries(archive.toByteArray()).keySet()).containsExactly("original.png");
        assertThat(downloadedAt(originalId)).isEqualTo(EXPORTED_AT);
        assertThat(downloadedAt(lateId[0])).isNull();
        assertThat(archiveCount()).isEqualTo(1L);
    }

    @Test
    void emptyExportCreatesNoArchiveAndNoMarkers() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        int written = rejectedExports.writeZip(null, null, false, archive, ADMIN);

        assertThat(written).isZero();
        assertThat(readZipEntries(archive.toByteArray())).isEmpty();
        assertThat(outboxCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_task WHERE rejected_downloaded_at IS NOT NULL",
                Long.class)).isZero();
    }

    @Test
    void outputFailureLeavesMarkersAndArchiveAbsent() throws Exception {
        String imageId = insertRejectedExportImage(405, "write-failure.png", new byte[]{1, 2, 3});
        OutputStream failingOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("simulated output failure");
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("simulated output failure");
            }
        };

        assertThatThrownBy(() -> rejectedExports.writeZip(null, null, false,
                failingOutput, ADMIN)).isInstanceOf(IOException.class);

        assertThat(downloadedAt(imageId)).isNull();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void flushFailureLeavesMarkersAndArchiveAbsent() throws Exception {
        String imageId = insertRejectedExportImage(406, "flush-failure.png", new byte[]{1, 2, 3});
        OutputStream failingOutput = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                throw new IOException("simulated flush failure");
            }
        };

        assertThatThrownBy(() -> rejectedExports.writeZip(null, null, false,
                failingOutput, ADMIN)).isInstanceOf(IOException.class);

        assertThat(downloadedAt(imageId)).isNull();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void repeatOnlyExportDoesNotCloseTheCurrentCycle() throws Exception {
        UUID cycleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO slack_notification_state
                    (id, active_cycle_id, active_message_ts, legacy_pointer_retired)
                VALUES (1, ?, '171234.1', TRUE)
                """, cycleId);
        String imageId = insertRejectedExportImage(407, "repeat.png", new byte[]{1});
        jdbc.update("UPDATE review_task SET rejected_downloaded_at = ? WHERE image_id = ?",
                Timestamp.from(EXPORTED_AT.minusSeconds(60)), imageId);

        int written = rejectedExports.writeZip(null, null, true,
                new ByteArrayOutputStream(), ADMIN);

        assertThat(written).isEqualTo(1);
        assertThat(outboxCount()).isZero();
        assertThat(activeCycle()).isEqualTo(cycleId);
        assertThat(activeMessage()).isEqualTo("171234.1");
        assertThat(downloadedAt(imageId)).isEqualTo(EXPORTED_AT.minusSeconds(60));
    }

    @Test
    void mixedExportReportsZipCountAndMarksOnlyNewImages() throws Exception {
        byte[] oldBytes = {1};
        byte[] newBytes = {2};
        String oldId = insertRejectedExportImage(408, "old.png", oldBytes);
        String newId = insertRejectedExportImage(409, "new.png", newBytes);
        Instant oldDownloadedAt = EXPORTED_AT.minusSeconds(60);
        jdbc.update("UPDATE review_task SET rejected_downloaded_at = ? WHERE image_id = ?",
                Timestamp.from(oldDownloadedAt), oldId);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        int written = rejectedExports.writeZip(null, null, true, archive, ADMIN);

        assertThat(written).isEqualTo(2);
        assertThat(readZipEntries(archive.toByteArray()).keySet())
                .containsExactlyInAnyOrder("old.png", "new.png");
        assertThat(downloadedAt(oldId)).isEqualTo(oldDownloadedAt);
        assertThat(downloadedAt(newId)).isEqualTo(EXPORTED_AT);
        assertThat(archiveCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT archive_payload FROM slack_notification_outbox "
                        + "WHERE operation_kind = 'ARCHIVE'", String.class))
                .contains("*2 files* downloaded by `admin`");
    }

    @Test
    void archiveInsertFailureRollsBackNewMarkers() throws Exception {
        String imageId = insertRejectedExportImage(410, "archive-failure.png", new byte[]{1});
        doThrow(new org.springframework.dao.DataIntegrityViolationException("archive insert failed"))
                .when(outbox)
                .enqueueArchive(any(UUID.class), eq(EXPORTED_AT), anyString(), eq(true));

        assertThatThrownBy(() -> rejectedExports.writeZip(null, null, false,
                new ByteArrayOutputStream(), ADMIN))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(downloadedAt(imageId)).isNull();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void disablingSlackPreservesTheBoundaryOfAnAlreadyVisibleCycle() throws Exception {
        outbox.initializeState();
        UUID previousCycle = activeCycle();
        outbox.attachMessageToCycle(previousCycle, "existing-active");
        doReturn(false).when(slackProperties).enabled();
        String imageId = insertRejectedExportImage(411, "disabled-active.png", new byte[]{1});

        rejectedExports.writeZip(null, null, false, new ByteArrayOutputStream(), ADMIN);

        assertThat(downloadedAt(imageId)).isEqualTo(EXPORTED_AT);
        assertThat(archiveCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_message_ts FROM slack_notification_outbox "
                + "WHERE operation_kind='ARCHIVE'", String.class)).isEqualTo("existing-active");
        assertThat(activeMessage()).isNull();
        assertThat(activeCycle()).isNotEqualTo(previousCycle);
        assertThat(outboxCount()).isEqualTo(1); // No new disabled-period notification cycle.
    }

    @Test
    void disabledSlackDoesNotAccumulateUntrackedExportHistory() throws Exception {
        doReturn(false).when(slackProperties).enabled();
        String imageId = insertRejectedExportImage(412, "disabled-untracked.png", new byte[]{1});

        rejectedExports.writeZip(null, null, false, new ByteArrayOutputStream(), ADMIN);

        assertThat(downloadedAt(imageId)).isEqualTo(EXPORTED_AT);
        assertThat(outboxCount()).isZero();
    }

    private long archiveCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM slack_notification_outbox "
                        + "WHERE operation_kind = 'ARCHIVE'", Long.class);
    }

    private long outboxCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM slack_notification_outbox", Long.class);
    }

    private Instant downloadedAt(String imageId) {
        Timestamp value = jdbc.queryForObject(
                "SELECT rejected_downloaded_at FROM review_task WHERE image_id = ?",
                Timestamp.class,
                imageId);
        return value == null ? null : value.toInstant();
    }

    private UUID activeCycle() {
        return jdbc.queryForObject(
                "SELECT active_cycle_id FROM slack_notification_state WHERE id = 1",
                UUID.class);
    }

    private String activeMessage() {
        return jdbc.queryForObject(
                "SELECT active_message_ts FROM slack_notification_state WHERE id = 1",
                String.class);
    }
}
