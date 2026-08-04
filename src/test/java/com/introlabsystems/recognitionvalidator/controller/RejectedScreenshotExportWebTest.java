package com.introlabsystems.recognitionvalidator.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.sql.Timestamp;
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

class RejectedScreenshotExportWebTest extends AbstractWebIntegrationTest {

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

        assertThat(readZipEntries(result.getResponse().getContentAsByteArray()))
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

        assertThat(readZipEntries(firstArchive))
                .containsExactlyEntriesOf(Map.of("repeatable-rejected.png", bytes));
        assertThat(readZipEntries(defaultRepeat)).isEmpty();
        assertThat(readZipEntries(explicitRepeat))
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

        assertThat(readZipEntries(archive).keySet()).containsExactly("inside-window.png");
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

        assertThat(readZipEntries(archive))
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
                failingOutput
        )).isInstanceOf(IOException.class);
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NULL FROM review_task WHERE image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
    }
}
