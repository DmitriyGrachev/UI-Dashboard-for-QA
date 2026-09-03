package com.introlabsystems.recognitionvalidator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import static com.introlabsystems.recognitionvalidator.ai.AiSettingsRepositoryTest.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "validator.integration.image-api-key=local-ai-test-key",
        "validator.ai-delivery.public-base-url=https://validator.example.com",
        "validator.ai-delivery.signing-key=01234567890123456789012345678901-test-only"
})
class AiTaskHttpTest extends AiTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AiSettingsRepository settings;
    @Autowired ValidatorProperties properties;
    static final String CLAIM = "/api/integration/ai/tasks/claim";
    static final String KEY = "local-ai-test-key";

    @Test
    void claimsDownloadsAndCompletesWithoutChangingOperator() throws Exception {
        String id = image(1, 53);
        Files.createDirectories(properties.imageRoot());
        Files.write(properties.imageRoot().resolve(id + ".png"), new byte[]{1, 2, 3});
        settings.save(new AiSettings(0, true, List.of(rule(10, null))));
        String response = mvc.perform(post(CLAIM).header("X-API-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].expected").value("7K"))
                .andExpect(jsonPath("$.items[0].game").value("SINGLE_DECK"))
                .andReturn().getResponse().getContentAsString();
        var item = json.readTree(response).path("items").get(0);
        URI url = URI.create(item.path("url").asText());
        mvc.perform(get(url.getRawPath() + "?" + url.getRawQuery())).andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(get(url.getRawPath() + "?" + url.getRawQuery().replace("signature=", "signature=bad")))
                .andExpect(status().isUnauthorized());
        String result = """
                {"claimId":"%s","valid":true,"verdict":"MATCH","certainty":97,"message":"<script>example</script>"}
                """.formatted(item.path("claimId").asText());
        String resultPath = "/api/integration/ai/tasks/" + id + "/result";
        mvc.perform(post(resultPath).header("X-API-Key", KEY).contentType("application/json").content(result))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post(resultPath).header("X-API-Key", KEY).contentType("application/json").content(result)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE image_id=?", String.class, id)).isEqualTo("PENDING");
    }

    @Test
    void authorizationSizeAndDisabledQueueHaveStableContract() throws Exception {
        mvc.perform(post(CLAIM)).andExpect(status().isUnauthorized());
        mvc.perform(post(CLAIM).header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post(CLAIM).header("X-API-Key", KEY)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        for (String invalid : List.of("0", "21", "-1", "1.5", "banana"))
            mvc.perform(post(CLAIM).param("size", invalid).header("X-API-Key", KEY)).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/api/ai-queue/settings").header("X-API-Key", KEY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/api/ai-queue/settings").with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/api/ai-queue/settings").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void resultRejectsScalarCoercionFractionsAndOversizedBodies() throws Exception {
        String id = image(1, 53);
        String path = "/api/integration/ai/tasks/" + id + "/result";
        String template = "{\"claimId\":\"314fd2b3-0e1a-46e8-a974-a5a99d40a01b\",\"valid\":%s,\"verdict\":\"MATCH\",\"certainty\":%s}";
        for (String invalid : List.of(template.formatted("\"true\"", "97"), template.formatted("true", "97.5"),
                template.formatted("true", "\"97\""), template.formatted("false", "97"), template.formatted("true", "101"))) {
            mvc.perform(post(path).header("X-API-Key", KEY).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(path).header("X-API-Key", KEY).contentType("application/json").content(" ".repeat(17000)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post(path).header("X-API-Key", KEY).contentType("application/json")
                .content(template.formatted("true", "97").replace("\"certainty\":97", "\"certainty\":97,\"message\":123")))
                .andExpect(status().isBadRequest());
    }
}
