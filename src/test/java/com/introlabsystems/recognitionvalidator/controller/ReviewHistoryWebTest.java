package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewHistoryWebTest extends AbstractWebIntegrationTest {
    @Test
    void historyIsPrivateCursorPagedAndDoesNotChangeAssignmentsOrStatistics() throws Exception {
        UUID operator = insertOperator("history-operator", "password");
        UUID other = insertOperator("history-other", "password");
        var principal = new OperatorPrincipal(operator, "history-operator", "unused", true);
        for (int i = 1; i <= 27; i++) {
            String id = insertReviewImage(i, "screen-" + i + ".png", true, i % 2 == 0 ? "bj_igt" : "bj_netent", "history", null, "Two", null);
            jdbc.update("UPDATE review_task SET status='COMPLETED',assigned_to=?,reviewed_at='2026-09-29T10:00:00Z',decision=? WHERE image_id=?",
                    operator, i % 2 == 0 ? "ACCEPTED" : "REJECTED", id);
        }
        String privateId = insertReviewImage(30, "other-private.png", true, "bj_igt", "other", null, "Two", null);
        jdbc.update("UPDATE review_task SET status='COMPLETED',assigned_to=?,reviewed_at=now(),decision='ACCEPTED' WHERE image_id=?", other, privateId);
        String active = insertReviewImage(31, "active.png", true, "bj_igt", "active", null, "Two", null);
        jdbc.update("UPDATE review_task SET status='ASSIGNED',assigned_to=?,assigned_at=now(),lease_expires_at=now()+interval '30 minutes' WHERE image_id=?", operator, active);
        insertReviewImage(32, "pending.png", true, "bj_igt", "pending", null, "Two", null);
        var tasksBefore = jdbc.queryForList("SELECT * FROM review_task ORDER BY image_id");
        var statsBefore = jdbc.queryForList("SELECT * FROM operator_daily_statistics");

        String html = mockMvc.perform(get("/history").with(user(principal))).andExpect(status().isOk())
                .andExpect(view().name("review-history")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("screen-27.png", "screen-3.png", "bj_netent", "bj_igt", "Matches", "Does not match", "UTC", "Older reviews")
                .doesNotContain("screen-2.png", "screen-1.png", "other-private.png", "active.png", "pending.png");
        assertThat(html.indexOf("screen-27.png")).isLessThan(html.indexOf("screen-26.png"));
        fixture("review-history.html", html);
        String older = mockMvc.perform(get("/history").with(user(principal))
                        .param("beforeAt", "2026-09-29T10:00:00Z").param("beforeId", "%064x".formatted(3)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(older).contains("screen-2.png", "screen-1.png").doesNotContain("screen-3.png", "Older reviews");
        assertThat(jdbc.queryForList("SELECT * FROM review_task ORDER BY image_id")).isEqualTo(tasksBefore);
        assertThat(jdbc.queryForList("SELECT * FROM operator_daily_statistics")).isEqualTo(statsBefore);
    }

    @Test
    void emptyHistoryAndInvalidCursorsAreHandled() throws Exception {
        var principal = new OperatorPrincipal(UUID.randomUUID(), "new-operator", "unused", true);
        String html = mockMvc.perform(get("/history").with(user(principal))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("No reviews to show", "Back to review");
        fixture("review-history-empty.html", html);
        mockMvc.perform(get("/history").with(user(principal)).param("beforeAt", Instant.now().toString())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/history").with(user(principal)).param("beforeId", "invalid").param("beforeAt", Instant.now().toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/history").with(user(principal)).param("beforeId", "%064x".formatted(1))).andExpect(status().isBadRequest());
    }

    @Test
    void historyRequiresAnOperatorSession() throws Exception {
        mockMvc.perform(get("/history")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/history").with(user("admin").roles("ADMIN")))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin"));
    }

    private void fixture(String name, String html) throws Exception {
        if (!Boolean.getBoolean("validator.write-browser-fixtures")) return;
        Files.createDirectories(Path.of("target", "browser-fixtures"));
        Files.writeString(Path.of("target", "browser-fixtures", name), html);
    }
}
