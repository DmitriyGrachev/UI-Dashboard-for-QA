package com.introlabsystems.recognitionvalidator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.introlabsystems.recognitionvalidator.ai.controller.AiTaskController;
import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import com.introlabsystems.recognitionvalidator.config.OpenApiConfig;
import com.introlabsystems.recognitionvalidator.security.SecurityConfig;
import com.introlabsystems.recognitionvalidator.security.SecurityFailureHandler;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AiTaskController.class, ImageContentController.class})
@Import({SecurityConfig.class, SecurityFailureHandler.class, OpenApiConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, SwaggerUiConfigProperties.class,
        SwaggerUiOAuthProperties.class, SwaggerConfig.class})
class OpenApiWebTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AiQueueService queue;
    @MockitoBean ImageStorageService storage;
    @MockitoBean UserDetailsService userDetailsService;

    @Test
    void publishesOnlyIntegrationEndpointsWithApiKeyExamplesAndPngSchema() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = json.readTree(response);

        assertThat(document.path("paths").has("/api/integration/ai/tasks/claim")).isTrue();
        assertThat(document.path("paths").has("/api/integration/ai/tasks/{imageId}/result")).isTrue();
        assertThat(document.path("paths").has("/api/integration/images/{imageId}/content")).isTrue();
        assertThat(document.path("paths").has("/api/images/{imageId}/content")).isFalse();
        assertThat(document.at("/components/securitySchemes/IntegrationApiKey/name").asText()).isEqualTo("X-API-Key");
        assertThat(document.path("paths").path("/api/integration/ai/tasks/{imageId}/result")
                .path("post").path("requestBody").path("content").path("application/json").path("examples").isObject()).isTrue();
        assertThat(document.path("paths").path("/api/integration/images/{imageId}/content")
                .path("get").path("responses").path("200").path("content").path("image/png").path("schema").path("format").asText())
                .isEqualTo("binary");
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }
}
