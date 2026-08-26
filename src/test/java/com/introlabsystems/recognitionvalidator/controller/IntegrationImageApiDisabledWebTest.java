package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.security.SecurityConfig;
import com.introlabsystems.recognitionvalidator.security.SecurityFailureHandler;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ImageContentController.class,
        properties = "validator.integration.image-api-key=")
@Import({SecurityConfig.class, SecurityFailureHandler.class})
class IntegrationImageApiDisabledWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ImageStorageService storage;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void unconfiguredKeyKeepsTheApiClosedIncludingForAnEmptyHeader() throws Exception {
        String url = "/api/integration/images/" + "a".repeat(64) + "/content";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("X-API-Key", "")).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("X-API-Key", "some-key")).andExpect(status().isUnauthorized());

        verifyNoInteractions(storage);
    }
}
