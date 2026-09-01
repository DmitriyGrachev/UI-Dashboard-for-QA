package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminScreenshotDeliveryWebTest extends AbstractWebIntegrationTest {

    private static final String CLOUD_KEY = "validator/admin-cloud.png";

    @MockitoBean
    private CloudObjectStorage cloudStorage;

    @Test
    void adminCanPreviewAndDownloadOneLocalPng() throws Exception {
        byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 8, 9};
        Files.write(imageRoot.resolve("admin-local.png"), bytes);
        String imageId = insertReviewImage(
                721, "admin-local.png", true, "bj_americas_ags", "admin-local-session",
                "Eight", "Three_Four", null
        );
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), imageId
        );

        mockMvc.perform(get("/admin/api/screenshots/{imageId}/content", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline")
                ))
                .andExpect(content().bytes(bytes));

        mockMvc.perform(get("/admin/api/screenshots/{imageId}/download", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")
                ))
                .andExpect(content().bytes(bytes));

        mockMvc.perform(get("/admin/api/screenshots/{imageId}/availability", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void cloudScreenshotCanBePreviewedDownloadedAndOpenedWithTemporaryLink() throws Exception {
        byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 4, 2};
        String imageId = insertReviewImage(
                722, "admin-cloud.png", false, "bj_igt", "admin-cloud-session",
                null, "Queen_Five", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        URI signedUrl = URI.create("https://download.example.invalid/admin-cloud.png?signature=short");
        when(cloudStorage.presignGet(CLOUD_KEY, Duration.ofMinutes(15)))
                .thenReturn(signedUrl);
        when(cloudStorage.open(CLOUD_KEY)).thenReturn(
                new CloudObjectStorage.CloudContent(new ByteArrayInputStream(bytes), bytes.length)
        );

        mockMvc.perform(get("/admin/api/screenshots/{imageId}/content", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string(HttpHeaders.LOCATION, signedUrl.toString()));

        mockMvc.perform(get("/admin/api/screenshots/{imageId}/download", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")
                ))
                .andExpect(content().bytes(bytes));

        mockMvc.perform(get("/admin/api/screenshots/{imageId}/temporary-link", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(signedUrl.toString()))
                .andExpect(jsonPath("$.expiresAt").isString());
    }

    @Test
    void temporaryLinkRequiresACloudCopy() throws Exception {
        Files.write(imageRoot.resolve("local-only.png"), new byte[]{1, 2, 3});
        String imageId = insertReviewImage(
                723, "local-only.png", true, "bj_igt", "local-only-session",
                null, "Ace_Six", null
        );

        mockMvc.perform(get("/admin/api/screenshots/{imageId}/temporary-link", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Image is not available in B2"));
    }

    @Test
    void operatorCannotUseAdminScreenshotDelivery() throws Exception {
        mockMvc.perform(get("/admin/api/screenshots/{imageId}/content", "f".repeat(64))
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/review"));
    }
}
