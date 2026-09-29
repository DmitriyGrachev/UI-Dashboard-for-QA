package com.introlabsystems.recognitionvalidator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRule;
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
    void exposesDoubleDeckAgsAsAvailableRuleGame() throws Exception {
        mvc.perform(get("/admin/api/ai-queue/settings").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games").value(org.hamcrest.Matchers.hasItem("bj_double_deck_ags")));
    }

    @Test
    void savedReorderChangesNextClaimsButPreservesActiveClaimsAndRejectsStaleSaves() throws Exception {
        String oldest = image(1, 1);
        String specificFirst = image(2, 53);
        String specificNext = image(3, 53);
        String broadNext = image(4, 1);
        Files.createDirectories(properties.imageRoot());
        for (String id : List.of(oldest, specificFirst, specificNext, broadNext))
            Files.write(properties.imageRoot().resolve(id + ".png"), new byte[]{1});
        AiRule disabled = new AiRule(java.util.UUID.randomUUID(), "Disabled", false, 1,
                "bj_single_deck_ags", null, null, 1L, null, null);
        AiRule empty = rule(2, 999L);
        AiRule specific = rule(3, 53L);
        AiRule broad = rule(4, null);
        String path = "/admin/api/ai-queue/settings";
        AiSettings original = new AiSettings(0, true, List.of(broad, specific, empty, disabled));
        mvc.perform(put(path).with(user("rules-admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsBytes(original)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        String active = mvc.perform(post(CLAIM).param("size", "1").header("X-API-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].imageId").value(specificFirst))
                .andReturn().getResponse().getContentAsString();
        AiRule raised = new AiRule(broad.id(), broad.name(), true, 3, broad.gameCode(),
                null, null, null, null, null);
        AiRule lowered = new AiRule(specific.id(), specific.name(), true, 4, specific.gameCode(),
                null, null, 53L, null, null);
        mvc.perform(put(path).with(user("rules-admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsBytes(
                                new AiSettings(1, true, List.of(disabled, empty, raised, lowered)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put(path).with(user("other-admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsBytes(original)))
                .andExpect(status().isConflict());
        mvc.perform(get(path).with(user("rules-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules[2].id").value(broad.id().toString()));
        mvc.perform(post(CLAIM).param("size", "3").header("X-API-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].imageId").value(oldest))
                .andExpect(jsonPath("$.items[1].imageId").value(specificNext))
                .andExpect(jsonPath("$.items[2].imageId").value(broadNext));
        assertThat(jdbc.queryForObject("SELECT claim_id::text FROM ai_review_task WHERE image_id=?",
                String.class, specificFirst)).isEqualTo(json.readTree(active).path("items").get(0).path("claimId").asText());
        assertThat(jdbc.queryForObject("SELECT issued_rule_id FROM ai_review_task WHERE image_id=?",
                java.util.UUID.class, specificFirst)).isEqualTo(specific.id());
        assertThat(jdbc.queryForList("SELECT issued_rule_id FROM ai_review_task WHERE image_id<>?",
                java.util.UUID.class, specificFirst)).containsOnly(broad.id());
    }

    @Test
    void claimReturnsOriginalFilenameWithoutParsingExpectedAndUsesTenMinuteLease() throws Exception {
        String id = image(99, 53);
        String name = "original screenshot 99.png";
        Files.createDirectories(properties.imageRoot().resolve("nested"));
        Files.write(properties.imageRoot().resolve("nested").resolve(name), new byte[]{1});
        jdbc.update("UPDATE image_asset SET file_name=?, relative_path=?, payload_raw='unparseable' WHERE id=?",
                name, "nested/" + name, id);
        settings.save(new AiSettings(0, true, List.of(rule(1, null))));
        var before = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        var response = mvc.perform(post(CLAIM).header("X-API-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].imageName").value(name))
                .andExpect(jsonPath("$.items[0].expected").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var item = json.readTree(response).path("items").get(0);
        var expires = java.time.Instant.parse(item.path("leaseExpiresAt").asText());
        assertThat(expires).isBetween(before.plusSeconds(600), before.plusSeconds(610));
        assertThat(URI.create(item.path("url").asText()).getQuery())
                .startsWith("expires=" + expires.plusSeconds(30).getEpochSecond() + "&");
    }

    @Test
    void claimsDownloadsAndCompletesWithoutChangingOperator() throws Exception {
        String id = image(1, 53);
        Files.createDirectories(properties.imageRoot());
        Files.write(properties.imageRoot().resolve(id + ".png"), new byte[]{1, 2, 3});
        settings.save(new AiSettings(0, true, List.of(rule(1, null))));
        String response = mvc.perform(post(CLAIM).header("X-API-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imageName").value(id + ".png"))
                .andExpect(jsonPath("$.items[0].expected").doesNotExist())
                .andExpect(jsonPath("$.items[0].game").value("bj_single_deck_ags"))
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

    @Test
    void rejectStoresTechnicalFailureAndMakesTaskHumanOnly() throws Exception {
        String id = image(1, 53);
        Files.createDirectories(properties.imageRoot());
        Files.write(properties.imageRoot().resolve(id + ".png"), new byte[]{1, 2, 3});
        settings.save(new AiSettings(0, true, List.of(rule(1, null))));
        String claimResponse = mvc.perform(post(CLAIM).header("X-API-Key", KEY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var item = json.readTree(claimResponse).path("items").get(0);
        String body = "{\"imageId\":\"%s\",\"claimId\":\"%s\",\"message\":\"image could not be decoded\"}"
                .formatted(id, item.path("claimId").asText());

        mvc.perform(post("/api/integration/ai/tasks/reject")
                        .header("X-API-Key", KEY).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(id))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(jdbc.queryForMap("SELECT status, claim_id, lease_expires_at, valid, verdict, checked_at, last_error_code, last_error_message FROM ai_review_task WHERE image_id=?", id))
                .containsEntry("status", "FAILED")
                .containsEntry("claim_id", null)
                .containsEntry("lease_expires_at", null)
                .containsEntry("valid", null)
                .containsEntry("verdict", null)
                .containsEntry("checked_at", null)
                .containsEntry("last_error_code", "AI_REJECTED")
                .containsEntry("last_error_message", "image could not be decoded");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_rejection WHERE image_id=?", Long.class, id)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE image_id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(mvc.perform(post(CLAIM).header("X-API-Key", KEY)).andReturn().getResponse().getContentAsString())
                .contains("\"items\":[]");

        mvc.perform(post("/api/integration/ai/tasks/reject")
                        .header("X-API-Key", KEY).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_rejection WHERE image_id=?", Long.class, id)).isOne();
    }

    @Test
    void rejectFencesExpiredAndCompletedClaims() throws Exception {
        String id = image(1, 53);
        Files.createDirectories(properties.imageRoot());
        Files.write(properties.imageRoot().resolve(id + ".png"), new byte[]{1, 2, 3});
        settings.save(new AiSettings(0, true, List.of(rule(1, null))));
        var item = json.readTree(mvc.perform(post(CLAIM).header("X-API-Key", KEY)).andReturn()
                .getResponse().getContentAsString()).path("items").get(0);
        String stale = "{\"imageId\":\"%s\",\"claimId\":\"%s\",\"message\":\"late\"}"
                .formatted(id, item.path("claimId").asText());
        jdbc.update("UPDATE ai_review_task SET lease_expires_at=now()-interval '1 second' WHERE image_id=?", id);
        mvc.perform(post("/api/integration/ai/tasks/reject").header("X-API-Key", KEY)
                        .contentType("application/json").content(stale))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_CLAIM"));

        var next = json.readTree(mvc.perform(post(CLAIM).header("X-API-Key", KEY)).andReturn()
                .getResponse().getContentAsString()).path("items").get(0);
        String resultPath = "/api/integration/ai/tasks/" + id + "/result";
        String result = "{\"claimId\":\"%s\",\"valid\":true,\"verdict\":\"MATCH\"}"
                .formatted(next.path("claimId").asText());
        mvc.perform(post(resultPath).header("X-API-Key", KEY).contentType("application/json").content(result))
                .andExpect(status().isOk());
        String afterResult = "{\"imageId\":\"%s\",\"claimId\":\"%s\",\"message\":\"too late\"}"
                .formatted(id, next.path("claimId").asText());
        mvc.perform(post("/api/integration/ai/tasks/reject").header("X-API-Key", KEY)
                        .contentType("application/json").content(afterResult))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESULT_CONFLICT"));
    }

    @Test
    void rejectRequiresIntegrationAuthAndBoundedFields() throws Exception {
        String id = image(1, 53);
        String valid = "{\"imageId\":\"%s\",\"claimId\":\"314fd2b3-0e1a-46e8-a974-a5a99d40a01b\",\"message\":\"failed\"}"
                .formatted(id);
        mvc.perform(post("/api/integration/ai/tasks/reject").contentType("application/json").content(valid))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integration/ai/tasks/reject").header("X-API-Key", KEY)
                        .contentType("application/json").content(valid.replace("failed", " ")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/integration/ai/tasks/reject").header("X-API-Key", KEY)
                        .contentType("application/json").content(valid.replace("failed", "x".repeat(1001))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/integration/ai/tasks/reject").header("X-API-Key", KEY)
                        .contentType("application/json").content(" ".repeat(16 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge());
    }
}
