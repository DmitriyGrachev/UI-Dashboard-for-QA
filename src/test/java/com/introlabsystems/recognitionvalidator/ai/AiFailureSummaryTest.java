package com.introlabsystems.recognitionvalidator.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AiFailureSummaryTest extends AiTestSupport {
    @Autowired MockMvc mvc;
    private static final String PATH = "/admin/api/ai-queue/operations/failures";

    @Test
    void groupsCurrentFailuresByExactReasonAndIssuingRuleIncludingMissingValues() throws Exception {
        var rule = UUID.randomUUID();
        jdbc.update("INSERT INTO ai_selection_rule(id,name,enabled,priority,game_code) VALUES (?,'Current rule',false,1,'bj_igt')", rule);
        for (int i = 1; i <= 7; i++) image(i, 53);
        jdbc.update("UPDATE ai_review_task SET status='FAILED',issued_rule_id=?,last_error_code='AI_REJECTED'", rule);
        jdbc.update("UPDATE ai_review_task SET issued_rule_id=?,last_error_code='NEW_CODE' WHERE image_id=?", UUID.randomUUID(), "%064x".formatted(3));
        jdbc.update("UPDATE ai_review_task SET issued_rule_id=NULL,last_error_code=NULL WHERE image_id=?", "%064x".formatted(4));
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',verdict='MISMATCH' WHERE image_id=?", "%064x".formatted(5));
        jdbc.update("UPDATE ai_review_task SET status='PROCESSING',lease_expires_at=now()-interval '1 hour' WHERE image_id=?", "%064x".formatted(6));
        jdbc.update("UPDATE ai_review_task SET status='PENDING' WHERE image_id=?", "%064x".formatted(7));
        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.groups.length()").value(3))
                .andExpect(jsonPath("$.groups[0].ruleId").value(rule.toString()))
                .andExpect(jsonPath("$.groups[0].ruleName").value("Current rule"))
                .andExpect(jsonPath("$.groups[0].errorCode").value("AI_REJECTED"))
                .andExpect(jsonPath("$.groups[0].count").value(2))
                .andExpect(jsonPath("$.groups[1].ruleName").isEmpty())
                .andExpect(jsonPath("$.groups[1].errorCode").value("NEW_CODE"))
                .andExpect(jsonPath("$.groups[2].ruleId").isEmpty())
                .andExpect(jsonPath("$.groups[2].errorCode").isEmpty());
    }

    @Test
    void emptySummaryAndAdminAuthorization() throws Exception {
        mvc.perform(get(PATH).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.groups").isEmpty());
        mvc.perform(get(PATH).accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
    }
}
