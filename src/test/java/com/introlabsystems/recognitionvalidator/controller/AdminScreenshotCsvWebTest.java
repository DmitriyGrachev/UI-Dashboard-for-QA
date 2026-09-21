package com.introlabsystems.recognitionvalidator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminScreenshotCsvWebTest extends AbstractWebIntegrationTest {
    @Autowired ObjectMapper mapper;
    private static final String EXPORT = "/admin/api/screenshots/export.csv";

    @Test
    void failedTaskFiltersAgreeAcrossSearchSummaryAndCsvAndExposeDiagnostics() throws Exception {
        String rule = "11111111-1111-1111-1111-111111111111";
        String other = "22222222-2222-2222-2222-222222222222";
        for (int i = 1; i <= 6; i++) {
            String id = insertReviewImage(i, "failed-" + i + ".png", false,
                    i % 2 == 0 ? "bj_igt" : "bj_single_deck_ags", "diagnostics", null, "Two", null);
            jdbc.update("""
                    INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,is_notification,has_user_hand,
                        issued_rule_id,attempt_count,last_error_code,last_error_message,last_error_at,valid,verdict)
                    SELECT image_id,?,file_created_at,game_code,false,true,?::uuid,3,'AI_REJECTED',?,now(),false,'MISMATCH'
                    FROM review_task WHERE image_id=?
                    """, i <= 3 ? "FAILED" : i == 4 ? "COMPLETED" : i == 5 ? "PROCESSING" : "PENDING",
                    i == 3 ? other : rule, "<script>untrusted error</script>", id);
        }
        for (var filters : List.of(Map.of("aiTaskStatus", "FAILED"),
                Map.of("aiTaskStatus", "FAILED", "issuedRuleId", rule),
                Map.of("issuedRuleId", other), Map.of("aiTaskStatus", "FAILED", "gameCode", "bj_igt"),
                Map.of("aiTaskStatus", "FAILED", "aiResult", "UNCHECKED"),
                Map.of("aiTaskStatus", "PROCESSING"), Map.of("aiTaskStatus", "COMPLETED"), Map.of("aiTaskStatus", "PENDING"),
                Map.of("aiTaskStatus", "FAILED", "aiResult", "CHECKED"))) {
            var result = mapper.readTree(mockMvc.perform(request("/admin/api/screenshots", filters))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            List<String> ids = new ArrayList<>();
            result.get("items").forEach(item -> ids.add(item.get("imageId").asText()));
            int expected = "CHECKED".equals(filters.get("aiResult")) ? 0 : filters.containsKey("gameCode") ? 1
                    : filters.containsKey("aiTaskStatus") && !"FAILED".equals(filters.get("aiTaskStatus")) ? 1
                    : rule.equals(filters.get("issuedRuleId")) ? 2 : other.equals(filters.get("issuedRuleId")) ? 1 : 3;
            assertThat(ids).as("filters %s", filters).hasSize(expected);
            mockMvc.perform(request("/admin/api/screenshots/summary", filters))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(expected));
            var csv = parse(mockMvc.perform(request(EXPORT, filters)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(csv.subList(1, csv.size()).stream().map(row -> row.getFirst()).toList()).containsExactlyElementsOf(ids);
        }
        mockMvc.perform(request("/admin/api/screenshots/" + "%064x".formatted(1), Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ai.status").value("FAILED"))
                .andExpect(jsonPath("$.ai.attemptCount").value(3))
                .andExpect(jsonPath("$.ai.issuedRuleId").value(rule))
                .andExpect(jsonPath("$.ai.lastErrorMessage").value("<script>untrusted error</script>"));
    }

    @Test
    void operationalFiltersRejectInvalidStatusAndRuleId() throws Exception {
        for (String path : List.of("/admin/api/screenshots", "/admin/api/screenshots/summary", EXPORT)) {
            mockMvc.perform(request(path, Map.of("aiTaskStatus", "BROKEN"))).andExpect(status().isBadRequest());
            mockMvc.perform(request(path, Map.of("issuedRuleId", "not-a-uuid"))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void exportsAllPagesIncludingMissingFilesWithoutChangingTasks() throws Exception {
        for (int i = 1; i <= 103; i++) insertReviewImage(i, "missing-" + i + ".png", false, "bj_igt", "export", null, "Two", null);
        var before = jdbc.queryForList("SELECT * FROM review_task ORDER BY image_id");
        mockMvc.perform(get("/admin/api/screenshots").param("sessionId", "export").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(50));
        var response = mockMvc.perform(get(EXPORT).param("sessionId", "export").param("limit", "1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"screenshots.csv\""))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var rows = parse(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(rows).hasSize(104);
        assertThat(rows.get(1)).containsExactly("%064x".formatted(1), "missing-1.png", "bj_igt", "export",
                "2026-07-30T10:00:00Z", "", "", "", "", "", "UNCHECKED", "", "");
        assertThat(rows.get(103).getFirst()).isEqualTo("%064x".formatted(103));
        assertThat(jdbc.queryForList("SELECT * FROM review_task ORDER BY image_id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_review_task", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT file_available FROM image_asset", Boolean.class)).containsOnly(false);
    }

    @Test
    void everySearchFilterUsesTheSameSelectionAndOrderForCsv() throws Exception {
        var reviewer = insertOperator("Reviewer", "password");
        for (int i = 1; i <= 8; i++) insertReviewImage(i, "prefix_100%!_" + i + ".png", i % 2 == 0,
                i <= 4 ? "bj_igt" : "bj_single_deck_ags", i % 2 == 0 ? "even" : "odd", null, "Two", null);
        jdbc.update("UPDATE image_asset SET token_id=53, cloud_object_key='cloud', cloud_uploaded_at=now(), is_notification=true WHERE session_id='even'");
        jdbc.update("UPDATE review_task SET status='COMPLETED', decision='REJECTED', assigned_to=?, reviewed_at='2026-09-10T12:00:00Z', is_notification=true WHERE session_id='even'", reviewer);
        jdbc.update("""
                INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,is_notification,has_user_hand,
                  valid,verdict,confidence,checked_at)
                SELECT image_id,'COMPLETED',file_created_at,game_code,false,true,
                  session_id='even', CASE WHEN session_id='even' THEN 'MATCH' ELSE 'MISMATCH' END,
                  CASE WHEN session_id='even' THEN 95 ELSE 40 END,now()
                FROM review_task WHERE image_id <> ?
                """, "%064x".formatted(1));
        List<Map<String, String>> cases = List.of(
                Map.of(), Map.of("gameCode", "bj_igt"), Map.of("tokenId", "53"), Map.of("sessionId", "even"),
                Map.of("imageId", "%064x".formatted(3)), Map.of("fileName", "prefix_100%!_"),
                Map.of("createdFrom", "2026-07-30T10:00:00Z", "createdTo", "2026-07-31T00:00:00Z"),
                Map.of("createdTo", "2026-07-30T10:00:00Z"), Map.of("reviewState", "CHECKED"),
                Map.of("reviewState", "UNCHECKED"), Map.of("decision", "REJECTED"), Map.of("reviewedBy", "reviewer"),
                Map.of("reviewedFrom", "2026-09-10T00:00:00Z", "reviewedTo", "2026-09-11T00:00:00Z"),
                Map.of("storageState", "BOTH"), Map.of("storageState", "MISSING"), Map.of("parseStatus", "SUCCESS"),
                Map.of("notification", "true"), Map.of("hasUserHand", "false"), Map.of("aiResult", "UNCHECKED"),
                Map.of("aiResult", "CHECKED"), Map.of("aiResult", "MATCHED"), Map.of("aiResult", "UNMATCHED"),
                Map.of("aiVerdict", "MISMATCH"), Map.of("confidenceFrom", "95", "confidenceTo", "100"),
                Map.of("aiResult", "UNCHECKED", "aiVerdict", "MATCH"),
                Map.of("gameCode", "bj_igt", "reviewState", "CHECKED", "aiResult", "MATCHED", "tokenId", "53"));
        var before = jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id");
        for (var filters : cases) {
            var search = mapper.readTree(mockMvc.perform(request("/admin/api/screenshots", filters))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            List<String> ids = new ArrayList<>();
            search.get("items").forEach(item -> ids.add(item.get("imageId").asText()));
            var csv = parse(mockMvc.perform(request(EXPORT, filters)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(csv.subList(1, csv.size()).stream().map(row -> row.getFirst()).toList())
                    .as("filters %s", filters).containsExactlyElementsOf(ids);
        }
        assertThat(jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id")).isEqualTo(before);
    }

    @Test
    void writesAllColumnsEscapesCsvAndNeutralizesFormulas() throws Exception {
        String id = insertReviewImage(1, "file.png", false, "bj_igt", "session", null, "Two", null);
        var reviewer = insertOperator("@reviewer", "password");
        jdbc.update("UPDATE image_asset SET file_name=?, session_id=? WHERE id=?", "=HYPERLINK(\"evil\")", "+session,\"quoted\"", id);
        jdbc.update("UPDATE review_task SET status='COMPLETED', decision='ACCEPTED', assigned_to=?, reviewed_at=now()", reviewer);
        jdbc.update("""
                INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,is_notification,has_user_hand,
                  valid,verdict,confidence,message,checked_at)
                SELECT image_id,'COMPLETED',file_created_at,game_code,false,true,true,'MATCH',95,?,now() FROM review_task
                """, "\t=cmd,\"quoted\"\r\nдругая строка");
        String text = mockMvc.perform(request(EXPORT, Map.of())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(parse(text).get(1)).containsExactly(id, "'=HYPERLINK(\"evil\")", "bj_igt", "'+session,\"quoted\"",
                "2026-07-30T10:00:00Z", "COMPLETED", "MATCH", "true", "95", "'\t=cmd,\"quoted\"\r\nдругая строка",
                "CHECKED", "ACCEPTED", "'@reviewer");
        assertThat(text).contains("\"'=HYPERLINK(\"\"evil\"\")\"");
    }

    @Test
    void emptyExportIsAHeaderAndInvalidFiltersAreRejected() throws Exception {
        assertThat(parse(mockMvc.perform(request(EXPORT, Map.of())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8))).hasSize(1).first().asList().hasSize(13);
        for (var filters : List.of(Map.of("confidenceFrom", "101"), Map.of("reviewState", "UNCHECKED", "decision", "REJECTED"),
                Map.of("cursorId", "x"), Map.of("createdFrom", "invalid"),
                Map.of("createdFrom", "2026-09-10T00:00:00Z", "createdTo", "2026-09-09T00:00:00Z"))) {
            mockMvc.perform(request(EXPORT, filters)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void csvAndRuleStatisticsRequireAnAdminSession() throws Exception {
        mockMvc.perform(get(EXPORT)).andExpect(status().is3xxRedirection());
        mockMvc.perform(get(EXPORT).with(user("operator").roles("OPERATOR")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/review"));
        for (String path : List.of("/admin/api/ai-queue/operations/rules", "/admin/api/ai-queue/operations/activity")) {
            mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON).with(user("operator").roles("OPERATOR")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.revision").isNumber()).andExpect(jsonPath("$.rules").isArray());
        }
        mockMvc.perform(get("/admin/api/ai-queue/operations/rules").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.revision").isNumber()).andExpect(jsonPath("$.rules").isArray());
    }

    private MockHttpServletRequestBuilder request(String path, Map<String, String> filters) {
        var request = get(path).with(user("admin").roles("ADMIN"));
        filters.forEach(request::param);
        return request;
    }

    private static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = csv.startsWith("\uFEFF") ? 1 : 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (!quoted && ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (!quoted && ch == '\r' && csv.charAt(i + 1) == '\n') {
                row.add(cell.toString()); rows.add(row); row = new ArrayList<>(); cell.setLength(0); i++;
            } else cell.append(ch);
        }
        assertThat(quoted).isFalse();
        return rows;
    }
}
