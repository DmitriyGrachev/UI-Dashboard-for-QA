package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.exception.ImageStorageUnavailableException;
import com.introlabsystems.recognitionvalidator.security.SecurityConfig;
import com.introlabsystems.recognitionvalidator.security.SecurityFailureHandler;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ImageContentController.class,
        properties = "validator.integration.image-api-key=integration-test-key")
@Import({SecurityConfig.class, SecurityFailureHandler.class})
class IntegrationImageContentWebTest {

    private static final String IMAGE_ID = "a".repeat(64);
    private static final String URL = "/api/integration/images/" + IMAGE_ID + "/content";
    private static final String API_KEY = "integration-test-key";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ImageStorageService storage;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void validKeyStreamsLocalImageWithoutCreatingOrAuthenticatingASession() throws Exception {
        byte[] png = localImage();

        var result = mvc.perform(get(URL).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png))
                .andExpect(header().longValue("Content-Length", png.length))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verify(storage).open(IMAGE_ID);
    }

    @Test
    void cloudDeliveryIsProxiedAsPngWithoutExposingAStorageRedirect() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 4, 5, 6};
        URI location = URI.create("https://storage.example.invalid/frame.png?signature=test");
        when(storage.openForBrowser(IMAGE_ID))
                .thenReturn(new ImageStorageService.BrowserDelivery.Redirect(location));
        when(storage.open(IMAGE_ID)).thenReturn(imageContent(png));

        mvc.perform(get(URL).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().longValue("Content-Length", png.length));

        verify(storage).open(IMAGE_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-key", " "})
    void missingOrInvalidKeyReturnsJson401BeforeReadingStorage(String key) throws Exception {
        var request = get(URL);
        if (key != null) {
            request.header("X-API-Key", key);
        }

        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(header().doesNotExist("Location"));

        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_OPERATOR", "ROLE_ADMIN"})
    void browserSessionCannotReplaceTheApiKey(String role) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "browser-user", null, AuthorityUtils.createAuthorityList(role)));
        var session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);

        mvc.perform(get(URL).session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE", "HEAD"})
    void keyOnlyAllowsGetImageContent(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), URL).header("X-API-Key", API_KEY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(storage);
    }

    @Test
    void keyDoesNotGrantOperatorAdminOrOtherIntegrationAccess() throws Exception {
        mvc.perform(get("/api/images/{id}/content", IMAGE_ID).header("X-API-Key", API_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/review-tasks/claim").with(csrf()).header("X-API-Key", API_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/statistics/me").header("X-API-Key", API_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin").header("X-API-Key", API_KEY))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/api/integration/images/{id}/availability", IMAGE_ID)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(storage);
    }

    @Test
    void existingOperatorImageAccessStillWorksWithoutAnApiKey() throws Exception {
        byte[] png = localImage();
        when(storage.openForBrowser(IMAGE_ID))
                .thenReturn(new ImageStorageService.BrowserDelivery.Local(imageContent(png)));

        mvc.perform(get("/api/images/{id}/content", IMAGE_ID)
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(png));
    }

    @Test
    void authenticatedUiPostStillRequiresCsrf() throws Exception {
        mvc.perform(post("/api/review-tasks/claim")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingImageReturnsJson404() throws Exception {
        when(storage.open(IMAGE_ID)).thenThrow(new ImageNotFoundException(IMAGE_ID));

        mvc.perform(get(URL).header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void temporaryStorageFailureReturnsJson503() throws Exception {
        when(storage.open(IMAGE_ID)).thenThrow(new ImageStorageUnavailableException());

        mvc.perform(get(URL).header("X-API-Key", API_KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503));
    }

    private byte[] localImage() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
        when(storage.open(IMAGE_ID)).thenReturn(imageContent(png));
        return png;
    }

    private ImageStorageService.ImageContent imageContent(byte[] png) {
        return new ImageStorageService.ImageContent(
                new InputStreamResource(new ByteArrayInputStream(png)), png.length, "frame.png");
    }
}
