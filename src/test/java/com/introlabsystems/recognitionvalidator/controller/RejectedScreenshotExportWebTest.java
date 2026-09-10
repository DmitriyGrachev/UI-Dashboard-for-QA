package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.exception.ImageStorageUnavailableException;
import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.core.exception.SdkClientException.create;
import static software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder;

class RejectedScreenshotExportWebTest extends AbstractWebIntegrationTest {

    private static final String CLOUD_KEY = "validator/cloud-rejected.png";

    @MockitoBean
    private CloudObjectStorage cloudStorage;

    @Test
    void manifestContainsOnlyExportedImagesAndEscapesSpreadsheetText() throws Exception {
        String id = insertRejectedExportImage(390, "manifest.png", new byte[]{1});
        String missing = insertRejectedExportImage(391, "missing.png", new byte[]{2});
        Files.delete(imageRoot.resolve("missing.png"));
        jdbc.update("UPDATE image_asset SET session_id = ? WHERE id = ?", "=1+1", id);
        jdbc.update("""
                INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,session_id,
                    is_notification,has_user_hand,attempt_count,file_available,verdict,confidence,message)
                SELECT id,'COMPLETED',file_created_at,game_code,session_id,false,true,0,true,'MISMATCH',0,?
                FROM image_asset WHERE id = ?
                """, "Карта, \"дилер\"\nдругая", id);
        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var entries = readZipEntries(archive);
        assertThat(entries.keySet()).containsExactly("manifest.png", "results.csv");
        String csv = new String(entries.get("results.csv"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFfile_name,image_id,")
                .contains("\"'=1+1\"", "\"MISMATCH\",\"0\"", "Карта, \"\"дилер\"\"\nдругая", "\"REJECTED\"")
                .doesNotContain(missing);
    }

    @Test
    void aiMismatchExportIsIndependentRepeatableAndRespectsFilters() throws Exception {
        String[] verdicts = {"MISMATCH", "MISMATCH", "MATCH", "LOW_CONFIDENCE", "HAND_COUNT_MISMATCH", "NO_HANDS_FOUND", "MISMATCH", "MISMATCH"};
        for (int i = 0; i < verdicts.length; i++) {
            String imageId = insertRejectedExportImage(350 + i, "ai-" + i + ".png", new byte[]{(byte) i});
            jdbc.update("UPDATE image_asset SET session_id = ? WHERE id = ?", i == 6 ? "other-session" : "ai-session", imageId);
            jdbc.update("""
                    INSERT INTO ai_review_task(image_id, status, file_created_at, game_code,
                        session_id, is_notification, has_user_hand, attempt_count, file_available, verdict)
                    SELECT id, 'COMPLETED', file_created_at, game_code, session_id, false, true, 0, true, ?
                    FROM image_asset WHERE id = ?
                    """, verdicts[i], imageId);
            if (i == 0) jdbc.update("UPDATE review_task SET decision = 'ACCEPTED' WHERE image_id = ?", imageId);
            if (i == 1) jdbc.update("UPDATE review_task SET status = 'PENDING', decision = NULL, reviewed_at = NULL WHERE image_id = ?", imageId);
            if (i == 7) jdbc.update("UPDATE image_asset SET processed_at = '2026-07-31T10:00:00Z' WHERE id = ?", imageId);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                            .param("aiMismatch", "true").param("sessionId", "ai-session")
                            .param("processedFrom", "2026-07-30T00:00").param("processedTo", "2026-07-31T00:00")
                            .with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString("ai-mismatch-screenshots_")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(readZipImages(archive).keySet()).containsExactlyInAnyOrder("ai-0.png", "ai-1.png");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_task WHERE rejected_downloaded_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void exportSessionFilterIsExactAndOnlyMarksMatchingImagesDownloaded() throws Exception {
        String selected = insertRejectedExportImage(330, "session-selected.png", new byte[]{1});
        String other = insertRejectedExportImage(331, "session-other.png", new byte[]{2});
        jdbc.update("UPDATE image_asset SET session_id = ? WHERE id = ?", "session-1", selected);
        jdbc.update("UPDATE image_asset SET session_id = ? WHERE id = ?", "session-10", other);

        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .param("sessionId", " session-1 ")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(readZipImages(archive).keySet()).containsExactly("session-selected.png");
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class, other)).isTrue();

        byte[] remaining = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .param("sessionId", " ")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(readZipImages(remaining).keySet()).containsExactly("session-other.png");
    }

    @Test
    void archiveNameDescribesUtcPeriodIncludingOpenBounds() throws Exception {
        for (String[] period : new String[][]{
                {"2026-08-03T11:00", "2026-08-03T12:00", "2026-08-03_11-00_to_2026-08-03_12-00"},
                {"2026-08-03T11:00", "", "2026-08-03_11-00_to_latest"},
                {"", "2026-08-03T12:00", "start_to_2026-08-03_12-00"},
                {"", "", "start_to_latest"}
        }) {
            mockMvc.perform(post("/admin/rejected-screenshots.zip")
                            .param("processedFrom", period[0]).param("processedTo", period[1])
                            .with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString(
                            "rejected-screenshots_" + period[2] + "_UTC.zip")));
        }
    }

    @Test
    void adminDownloadsNewRejectedScreenshotsAndMarksThemDownloaded() throws Exception {
        byte[] rejectedBytes = new byte[]{1, 2, 3, 4};
        byte[] acceptedBytes = new byte[]{5, 6, 7, 8};
        Files.write(imageRoot.resolve("rejected.png"), rejectedBytes);
        Files.write(imageRoot.resolve("accepted.png"), acceptedBytes);
        String rejectedId = insertReviewImage(
                301, "rejected.png", true, "bj_igt", "rejected-session",
                null, "Jack", null
        );
        String acceptedId = insertReviewImage(
                302, "accepted.png", true, "bj_igt", "accepted-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                rejectedId
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'ACCEPTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                acceptedId
        );

        MvcResult result = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string(
                        "Content-Disposition",
                        containsString("rejected-screenshots")
                ))
                .andReturn();

        assertThat(readZipImages(result.getResponse().getContentAsByteArray()))
                .containsExactlyEntriesOf(Map.of("rejected.png", rejectedBytes));
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NOT NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                rejectedId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                acceptedId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportSkipsDownloadedUnlessExplicitlyIncluded() throws Exception {
        byte[] bytes = new byte[]{9, 8, 7, 6};
        Files.write(imageRoot.resolve("repeatable-rejected.png"), bytes);
        String imageId = insertReviewImage(
                303, "repeatable-rejected.png", true, "bj_igt", "repeat-session",
                null, "Queen", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );

        byte[] firstArchive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        byte[] defaultRepeat = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        byte[] explicitRepeat = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .param("includePreviouslyDownloaded", "true")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(readZipImages(firstArchive))
                .containsExactlyEntriesOf(Map.of("repeatable-rejected.png", bytes));
        assertThat(readZipImages(defaultRepeat)).isEmpty();
        assertThat(readZipImages(explicitRepeat))
                .containsExactlyEntriesOf(Map.of("repeatable-rejected.png", bytes));
    }

    @Test
    void rejectedScreenshotExportFiltersByProcessedAtWithInclusiveFromAndExclusiveTo()
            throws Exception {
        String beforeId = insertRejectedExportImage(304, "before-window.png", new byte[]{1});
        String includedId = insertRejectedExportImage(305, "inside-window.png", new byte[]{2});
        String atEndId = insertRejectedExportImage(306, "at-window-end.png", new byte[]{3});
        jdbc.update(
                "UPDATE image_asset SET processed_at = ?, file_created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-03T10:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-03T11:15:00Z")),
                beforeId
        );
        jdbc.update(
                "UPDATE image_asset SET processed_at = ?, file_created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-03T11:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-03T10:00:00Z")),
                includedId
        );
        jdbc.update(
                "UPDATE image_asset SET processed_at = ?, file_created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-03T12:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-03T11:30:00Z")),
                atEndId
        );

        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .param("processedFrom", "2026-08-03T11:00")
                        .param("processedTo", "2026-08-03T12:00")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(readZipImages(archive).keySet()).containsExactly("inside-window.png");
    }

    @Test
    void rejectedScreenshotExportRejectsAnInvalidDateRange() throws Exception {
        mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .param("processedFrom", "2026-08-03T12:00")
                        .param("processedTo", "2026-08-03T11:00")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectedScreenshotExportSkipsAndMarksMissingFilesUnavailable() throws Exception {
        String missingId = insertReviewImage(
                307, "already-deleted.png", true, "bj_igt", "missing-export-session",
                null, "King", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                missingId
        );
        byte[] availableBytes = new byte[]{4, 2};
        insertRejectedExportImage(308, "available-rejected.png", availableBytes);

        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(readZipImages(archive))
                .containsExactlyEntriesOf(Map.of("available-rejected.png", availableBytes));
        assertThat(jdbc.queryForObject(
                "SELECT file_available FROM image_asset WHERE id = ?",
                Boolean.class,
                missingId
        )).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                missingId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportDoesNotMarkFilesWhenTheDownloadFails() throws Exception {
        String imageId = insertRejectedExportImage(
                309,
                "failed-download.png",
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8}
        );
        OutputStream failingOutput = new OutputStream() {
            private int written;

            @Override
            public void write(int value) throws IOException {
                if (++written > 10) {
                    throw new IOException("Simulated client disconnect");
                }
            }
        };

        assertThatThrownBy(() -> rejectedExports.writeZip(
                null,
                null,
                false,
                failingOutput, "test-admin", null, false
        )).isInstanceOf(IOException.class);
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportExcludesExpiredCloudOnlyObject() throws Exception {
        String imageId = insertReviewImage(
                317, "expired-cloud-rejected.png", false, "bj_igt",
                "expired-cloud-rejected-session", null, "King", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = ? WHERE id = ?",
                CLOUD_KEY,
                Timestamp.from(Instant.now().minus(Duration.ofDays(21)).minusSeconds(1)),
                imageId
        );

        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(readZipImages(archive)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(cloudStorage);
    }

    @Test
    void rejectedScreenshotExportStreamsCloudOnlyPngAndMarksItDownloaded() throws Exception {
        byte[] bytes = new byte[]{7, 6, 5, 4};
        String imageId = insertReviewImage(
                310, "cloud-rejected.png", false, "bj_igt", "cloud-rejected-session",
                null, "Ace", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.open(CLOUD_KEY)).thenReturn(
                new CloudObjectStorage.CloudContent(new ByteArrayInputStream(bytes), bytes.length)
        );

        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(readZipImages(archive))
                .containsExactlyEntriesOf(Map.of("cloud-rejected.png", bytes));
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NOT NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportSkipsConfirmedMissingCloudObject() throws Exception {
        String imageId = insertReviewImage(
                311, "missing-cloud.png", false, "bj_igt", "missing-cloud-session",
                null, "King", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.open(CLOUD_KEY)).thenThrow(builder().message("missing").build());

        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(readZipImages(archive)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT cloud_object_key IS NULL AND cloud_uploaded_at IS NULL "
                        + "FROM image_asset WHERE id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportSkipsGenericCloud404() throws Exception {
        String imageId = insertReviewImage(
                313, "missing-generic-cloud.png", false, "bj_igt", "missing-generic-session",
                null, "King", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.open(CLOUD_KEY)).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder()
                        .statusCode(404)
                        .message("missing")
                        .build()
        );

        byte[] archive = mockMvc.perform(post("/admin/rejected-screenshots.zip")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(readZipImages(archive)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportClosesCloudStreamWhenZipHeaderFails() throws Exception {
        String imageId = insertReviewImage(
                314, "header-failure.png", false, "bj_igt", "header-failure-session",
                null, "Jack", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        TrackingInputStream stream = new TrackingInputStream(new byte[]{1, 2, 3});
        when(cloudStorage.open(CLOUD_KEY)).thenReturn(
                new CloudObjectStorage.CloudContent(stream, 3)
        );
        OutputStream failingOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("Simulated ZIP header failure");
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("Simulated ZIP header failure");
            }
        };

        assertThatThrownBy(() -> rejectedExports.writeZip(
                null,
                null,
                false,
                failingOutput, "test-admin", null, false
        )).isInstanceOf(IOException.class);
        assertThat(stream.closed).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportDoesNotMarkTransientCloudFailureDownloaded() throws Exception {
        String imageId = insertReviewImage(
                312, "transient-cloud.png", false, "bj_igt", "transient-cloud-session",
                null, "Jack", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.open(CLOUD_KEY)).thenThrow(create("temporary B2 outage"));

        assertThatThrownBy(() -> rejectedExports.writeZip(
                null,
                null,
                false,
                new ByteArrayOutputStream(), "test-admin", null, false
        )).isInstanceOf(ImageStorageUnavailableException.class);

        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportMapsTransientCloudReadFailureToUnavailable() throws Exception {
        String imageId = insertReviewImage(
                315, "transient-cloud-read.png", false, "bj_igt", "transient-cloud-read-session",
                null, "Jack", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        FailingCloudInputStream stream = new FailingCloudInputStream();
        when(cloudStorage.open(CLOUD_KEY)).thenReturn(
                new CloudObjectStorage.CloudContent(stream, 1)
        );

        assertThatThrownBy(() -> rejectedExports.writeZip(
                null,
                null,
                false,
                new ByteArrayOutputStream(), "test-admin", null, false
        )).isInstanceOf(ImageStorageUnavailableException.class);
        assertThat(stream.closed).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    @Test
    void rejectedScreenshotExportClosesPartialCloudStreamAndDoesNotMarkItDownloaded()
            throws Exception {
        String imageId = insertReviewImage(
                316, "partial-cloud-read.png", false, "bj_igt", "partial-cloud-session",
                null, "Jack", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        PartialFailingCloudInputStream stream = new PartialFailingCloudInputStream(
                new byte[]{7, 8, 9}
        );
        when(cloudStorage.open(CLOUD_KEY)).thenReturn(
                new CloudObjectStorage.CloudContent(stream, 6)
        );
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        assertThatThrownBy(() -> rejectedExports.writeZip(
                null,
                null,
                false,
                archive, "test-admin", null, false
        )).isInstanceOf(ImageStorageUnavailableException.class);

        assertThat(archive.size()).isPositive();
        assertThat(stream.closed).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FailingCloudInputStream extends java.io.InputStream {

        private boolean closed;

        @Override
        public int read() {
            throw create("temporary B2 read failure");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class PartialFailingCloudInputStream extends java.io.InputStream {

        private final byte[] prefix;
        private int position;
        private boolean closed;

        private PartialFailingCloudInputStream(byte[] prefix) {
            this.prefix = prefix;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (position >= prefix.length) {
                throw create("temporary B2 read failure after prefix");
            }
            int copied = Math.min(length, prefix.length - position);
            System.arraycopy(prefix, position, bytes, offset, copied);
            position += copied;
            return copied;
        }

        @Override
        public int read() {
            if (position >= prefix.length) {
                throw create("temporary B2 read failure after prefix");
            }
            return prefix[position++] & 0xFF;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
