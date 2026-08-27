package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static software.amazon.awssdk.core.exception.SdkClientException.create;

@TestPropertySource(properties = "validator.integration.image-api-key=integration-test-key")
class ImageContentDeliveryWebTest extends AbstractWebIntegrationTest {

    private static final String CLOUD_KEY = "validator/cloud-image.png";
    private static final String INTEGRATION_API_KEY = "integration-test-key";

    @MockitoBean
    private CloudObjectStorage cloudStorage;

    @Test
    void freshLocalImageReturnsPngWithoutCallingCloud() throws Exception {
        byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2};
        Files.write(imageRoot.resolve("fresh.png"), bytes);
        String imageId = insertReviewImage(
                401, "fresh.png", true, "bj_igt", "fresh-session",
                null, "Jack", null
        );
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), imageId
        );

        mockMvc.perform(get("/api/images/{imageId}/content", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes));

        org.mockito.Mockito.verifyNoInteractions(cloudStorage);
    }

    @Test
    void freshLocalAvailabilityReturnsAvailableWithoutCallingCloud() throws Exception {
        Files.write(imageRoot.resolve("fresh-availability.png"), new byte[]{1, 2, 3});
        String imageId = insertReviewImage(
                412, "fresh-availability.png", true, "bj_igt", "fresh-availability-session",
                null, "Jack", null
        );
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), imageId
        );

        mockMvc.perform(get("/api/images/{imageId}/availability", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verifyNoInteractions(cloudStorage);
    }

    @Test
    void oldCloudBackedImageReturnsNoStoreTemporaryRedirect() throws Exception {
        byte[] localBytes = new byte[]{1, 2, 3};
        Files.write(imageRoot.resolve("old.png"), localBytes);
        String imageId = insertReviewImage(
                402, "old.png", true, "bj_igt", "old-session",
                null, "Queen", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        var location = java.net.URI.create("https://download.example.invalid/private-object");
        when(cloudStorage.presignGet(CLOUD_KEY, Duration.ofMinutes(15)))
                .thenReturn(location);

        mockMvc.perform(get("/api/images/{imageId}/content", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string("Location", location.toString()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));

        verify(cloudStorage).presignGet(CLOUD_KEY, Duration.ofMinutes(15));
        verify(cloudStorage, times(0)).exists(CLOUD_KEY);
    }

    @Test
    void oldCloudBackedIntegrationImageIsProxiedAsPng() throws Exception {
        byte[] cloudBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 7, 8, 9};
        String imageId = insertReviewImage(
                414, "integration-cloud.png", false, "bj_igt", "integration-cloud-session",
                null, "King", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.open(CLOUD_KEY)).thenReturn(
                new CloudObjectStorage.CloudContent(
                        new ByteArrayInputStream(cloudBytes), cloudBytes.length
                )
        );

        mockMvc.perform(get("/api/integration/images/{imageId}/content", imageId)
                        .header("X-API-Key", INTEGRATION_API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(cloudBytes))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().longValue("Content-Length", cloudBytes.length))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(cloudStorage).open(CLOUD_KEY);
        org.mockito.Mockito.verify(cloudStorage, times(0))
                .presignGet(CLOUD_KEY, Duration.ofMinutes(15));
    }

    @Test
    void availabilityEndpointChecksCloudObjectOnceWithoutPresigning() throws Exception {
        String imageId = insertReviewImage(
                408, "verified.png", true, "bj_igt", "verified-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.exists(CLOUD_KEY)).thenReturn(true);

        mockMvc.perform(get("/api/images/{imageId}/availability", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isNoContent());

        verify(cloudStorage, times(1)).exists(CLOUD_KEY);
        org.mockito.Mockito.verify(cloudStorage, times(0))
                .presignGet(CLOUD_KEY, Duration.ofMinutes(15));
    }

    @Test
    void missingCloudObjectAvailabilityFallsBackToLocalAndClearsCloudState() throws Exception {
        byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 4, 5};
        Files.write(imageRoot.resolve("verified-local.png"), bytes);
        String imageId = insertReviewImage(
                409, "verified-local.png", true, "bj_igt", "verified-local-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.exists(CLOUD_KEY)).thenReturn(false);

        mockMvc.perform(get("/api/images/{imageId}/availability", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isNoContent());

        verify(cloudStorage, times(1)).exists(CLOUD_KEY);
        org.mockito.Mockito.verify(cloudStorage, times(0))
                .presignGet(CLOUD_KEY, Duration.ofMinutes(15));
        assertThat(jdbc.queryForObject(
                "SELECT cloud_object_key FROM image_asset WHERE id = ?",
                String.class,
                imageId
        )).isNull();
    }

    @Test
    void missingCloudOnlyObjectAvailabilityReturnsNotFoundAndClearsCloudState() throws Exception {
        String imageId = insertReviewImage(
                410, "verified-cloud-only.png", false, "bj_igt", "verified-cloud-only-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.exists(CLOUD_KEY)).thenReturn(false);

        mockMvc.perform(get("/api/images/{imageId}/availability", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isNotFound());

        verify(cloudStorage, times(1)).exists(CLOUD_KEY);
        assertThat(jdbc.queryForObject(
                "SELECT cloud_object_key FROM image_asset WHERE id = ?",
                String.class,
                imageId
        )).isNull();
    }

    @Test
    void missingCloudObjectAlsoMarksStaleLocalStateUnavailable() throws Exception {
        String imageId = insertReviewImage(
                413, "stale-local.png", true, "bj_igt", "stale-local-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.exists(CLOUD_KEY)).thenReturn(false);

        mockMvc.perform(get("/api/images/{imageId}/availability", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                "SELECT file_available FROM image_asset WHERE id = ?",
                Boolean.class,
                imageId
        )).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT cloud_object_key FROM image_asset WHERE id = ?",
                String.class,
                imageId
        )).isNull();
    }

    @Test
    void transientCloudAvailabilityFailureReturns503AndKeepsCloudState() throws Exception {
        String imageId = insertReviewImage(
                411, "verified-transient.png", false, "bj_igt", "verified-transient-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.exists(CLOUD_KEY)).thenThrow(create("temporary B2 outage"));

        mockMvc.perform(get("/api/images/{imageId}/availability", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Image storage is temporarily unavailable"));

        verify(cloudStorage, times(1)).exists(CLOUD_KEY);
        org.mockito.Mockito.verify(cloudStorage, times(0))
                .presignGet(CLOUD_KEY, Duration.ofMinutes(15));
        assertThat(jdbc.queryForObject(
                "SELECT cloud_object_key FROM image_asset WHERE id = ?",
                String.class,
                imageId
        )).isEqualTo(CLOUD_KEY);
    }

    @Test
    void missingLocalImageFallsBackToCloud() throws Exception {
        String imageId = insertReviewImage(
                403, "missing-local.png", false, "bj_igt", "missing-local-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        var location = java.net.URI.create("https://download.example.invalid/missing-local");
        when(cloudStorage.presignGet(CLOUD_KEY, Duration.ofMinutes(15)))
                .thenReturn(location);

        mockMvc.perform(get("/api/images/{imageId}/content", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string("Location", location.toString()));
    }

    @Test
    void expiredCloudOnlyImageReturnsNotFoundWithoutCreatingPresignedUrl() throws Exception {
        String imageId = insertReviewImage(
                407, "expired-cloud.png", false, "bj_igt", "expired-cloud-session",
                null, "Nine", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = ? WHERE id = ?",
                CLOUD_KEY,
                Timestamp.from(Instant.now().minus(Duration.ofDays(21)).minusSeconds(1)),
                imageId
        );

        mockMvc.perform(get("/api/images/{imageId}/content", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verifyNoInteractions(cloudStorage);
    }

    @Test
    void claimJsonKeepsAuthenticatedBrokerUrlInsteadOfCloudUrl() throws Exception {
        UUID operatorId = insertOperator("claim-operator", "password");
        String imageId = insertReviewImage(
                404, "claim.png", true, "bj_igt", "claim-session",
                null, "Ten", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );

        mockMvc.perform(post("/api/review-tasks/claim")
                .with(user(new OperatorPrincipal(operatorId, "claim-operator", "unused", true)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageUrl")
                        .value("/api/images/" + imageId + "/content"));
    }

    @Test
    void transientCloudFailureReturns503AndKeepsCloudState() throws Exception {
        String imageId = insertReviewImage(
                405, "transient.png", true, "bj_igt", "transient-session",
                null, "Seven", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        when(cloudStorage.presignGet(CLOUD_KEY, Duration.ofMinutes(15)))
                .thenThrow(create("temporary B2 outage"));

        mockMvc.perform(get("/api/images/{imageId}/content", imageId)
                        .with(user(principal(UUID.randomUUID(), "operator"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Image storage is temporarily unavailable"));

        assertThat(jdbc.queryForObject(
                "SELECT cloud_object_key FROM image_asset WHERE id = ?",
                String.class,
                imageId
        )).isEqualTo(CLOUD_KEY);
        assertThat(jdbc.queryForObject(
                "SELECT file_available FROM image_asset WHERE id = ?",
                Boolean.class,
                imageId
        )).isFalse();
    }

    @Test
    void unauthenticatedBrokerAccessRemainsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/images/{imageId}/content", "f".repeat(64)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void realOperatorSessionCanUseCloudBrokerButDisabledSessionCannotReceiveAnotherUrl()
            throws Exception {
        UUID operatorId = insertOperator("session-operator", "password");
        String imageId = insertReviewImage(
                406, "session-cloud.png", false, "bj_igt", "session-cloud",
                null, "Ace", null
        );
        jdbc.update(
                "UPDATE image_asset SET cloud_object_key = ?, cloud_uploaded_at = now() WHERE id = ?",
                CLOUD_KEY, imageId
        );
        var location = java.net.URI.create("https://download.example.invalid/session-object");
        when(cloudStorage.presignGet(CLOUD_KEY, Duration.ofMinutes(15)))
                .thenReturn(location);

        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "session-operator")
                        .param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/images/{imageId}/content", imageId).session(session))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string("Location", location.toString()));

        mockMvc.perform(post("/admin/operators/{id}/deactivate", operatorId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/images/{imageId}/content", imageId).session(session))
                .andExpect(status().isUnauthorized());

        verify(cloudStorage, times(1)).presignGet(CLOUD_KEY, Duration.ofMinutes(15));
    }
}
