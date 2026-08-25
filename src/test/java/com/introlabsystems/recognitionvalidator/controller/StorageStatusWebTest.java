package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

class StorageStatusWebTest extends AbstractWebIntegrationTest {

    @MockitoBean
    private CloudObjectStorage cloudStorage;

    @Test
    void adminReceivesAggregatedStorageStatus() throws Exception {
        String bothStores = insertReviewImage(
                501, "both.png", true, "bj_igt", "both", null, "Jack", null
        );
        String cloudOnly = insertReviewImage(
                502, "cloud-only.png", false, "bj_igt", "cloud-only", null, "Queen", null
        );
        String expiredCloud = insertReviewImage(
                507, "expired-cloud.png", true, "bj_igt", "expired-cloud", null, "Ace", null
        );
        String localPending = insertReviewImage(
                503, "local-pending.png", true, "bj_igt", "local-pending", null, "Ten", null
        );
        String retrying = insertReviewImage(
                504, "retrying.png", true, "bj_igt", "retrying", null, "Nine", null
        );
        String preparedMissing = insertReviewImage(
                505, "prepared-missing.png", false, "bj_igt", "prepared-missing", null, "Eight", null
        );
        insertReviewImage(
                506, "unavailable.png", false, "bj_igt", "unavailable", null, "Seven", null
        );

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = ? WHERE id = ?",
                "validator/both.png", Timestamp.from(now), bothStores
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = ? WHERE id = ?",
                "validator/cloud-only.png", Timestamp.from(now), cloudOnly
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = ? WHERE id = ?",
                "validator/expired-cloud.png", Timestamp.from(now.minusSeconds(22 * 24 * 60 * 60L)), expiredCloud
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_upload_next_attempt_at = ?, cloud_upload_attempt_count = ? "
                        + "WHERE id = ?",
                Timestamp.from(now.plusSeconds(300)), 2, retrying
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_upload_next_attempt_at = ?, "
                        + "cloud_upload_attempt_count = ? WHERE id = ?",
                "validator/prepared-missing.png", Timestamp.from(now.plusSeconds(300)), 1, preparedMissing
        );
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(600)), localPending
        );
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(500)), retrying
        );
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(400)), preparedMissing
        );

        mockMvc.perform(get("/admin/api/storage/status").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.uploaded").value(2))
                .andExpect(jsonPath("$.backlog").value(3))
                .andExpect(jsonPath("$.dueNow").value(1))
                .andExpect(jsonPath("$.retrying").value(2))
                .andExpect(jsonPath("$.localOnly").value(3))
                .andExpect(jsonPath("$.cloudOnly").value(1))
                .andExpect(jsonPath("$.bothStores").value(1))
                .andExpect(jsonPath("$.unavailable").value(1))
                .andExpect(jsonPath("$.oldestPendingAt").value(now.minusSeconds(600).toString()));

        verifyNoInteractions(cloudStorage);
    }

    @Test
    void operatorIsRedirectedAwayFromAdminStorageApi() throws Exception {
        mockMvc.perform(get("/admin/api/storage/status").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/review"));
    }

    @Test
    void anonymousIsRedirectedToLoginForAdminStorageApi() throws Exception {
        mockMvc.perform(get("/admin/api/storage/status"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login"));
    }
}
