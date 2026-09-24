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
    @Autowired com.introlabsystems.recognitionvalidator.ai.repository.AiOperationsRepository operations;
    private static final String PATH = "/admin/api/ai-queue/operations/failures";

    @Test
    void logPagesCurrentFailuresWithStableTiesAndKeepsMissingFilesRulesAndTimes() throws Exception {
        var deletedRule = UUID.randomUUID();
        for (int i = 1; i <= 48; i++) image(i, 53);
        jdbc.update("""
                UPDATE ai_review_task SET status='FAILED',issued_rule_id=?,attempt_count=3,
                  last_error_at='2026-09-23T12:00:00Z',last_error_code='AI_REJECTED',
                  last_error_message='<img onerror=alert(1)> unreadable cards'
                """, deletedRule);
        jdbc.update("UPDATE ai_review_task SET last_error_at=NULL,issued_rule_id=NULL,last_error_code=NULL WHERE image_id=?", "%064x".formatted(1));
        jdbc.update("UPDATE image_asset SET file_available=false WHERE id=?", "%064x".formatted(2));
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',verdict='MISMATCH' WHERE image_id=?", "%064x".formatted(46));
        jdbc.update("UPDATE ai_review_task SET status='PROCESSING' WHERE image_id=?", "%064x".formatted(47));
        jdbc.update("UPDATE ai_review_task SET status='PENDING' WHERE image_id=?", "%064x".formatted(48));
        jdbc.update("UPDATE review_task SET status='COMPLETED',decision='REJECTED' WHERE image_id=?", "%064x".formatted(48));
        var first = operations.failureLog(false, null, null, null, null, false, false);
        var second = operations.failureLog(false, first.nextAt(), first.nextId(), null, null, false, false);
        var third = operations.failureLog(false, second.nextAt(), second.nextId(), null, null, false, false);
        var all = java.util.stream.Stream.of(first, second, third).flatMap(page -> page.items().stream()).toList();
        org.assertj.core.api.Assertions.assertThat(first.items()).hasSize(20);
        org.assertj.core.api.Assertions.assertThat(second.items()).hasSize(20);
        org.assertj.core.api.Assertions.assertThat(third.items()).hasSize(5);
        org.assertj.core.api.Assertions.assertThat(third.nextId()).isNull();
        org.assertj.core.api.Assertions.assertThat(all).extracting(item -> item.imageId())
                .containsExactlyElementsOf(java.util.stream.IntStream.iterate(45, i -> i > 0, i -> i - 1).mapToObj(i -> "%064x".formatted(i)).toList());
        org.assertj.core.api.Assertions.assertThat(first.items().getFirst().ruleId()).isEqualTo(deletedRule);
        org.assertj.core.api.Assertions.assertThat(first.items().getFirst().ruleName()).isNull();
        org.assertj.core.api.Assertions.assertThat(all.getLast().failedAt()).isNull();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_review_task WHERE status='FAILED' AND attempt_count=3", Long.class)).isEqualTo(45);
        mvc.perform(get(PATH + "/tasks").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].errorMessage").value("<img onerror=alert(1)> unreadable cards"))
                .andExpect(jsonPath("$.items[0].attemptCount").value(3));
    }

    @Test
    void logCanShowOnlyAiServiceRejectionsWithoutDeliveryFailures() throws Exception {
        image(1, 53); image(2, 53);
        jdbc.update("UPDATE ai_review_task SET status='FAILED',last_error_code='DELIVERY_UNAVAILABLE'");
        jdbc.update("UPDATE ai_review_task SET last_error_code='AI_REJECTED' WHERE image_id=?", "%064x".formatted(1));
        mvc.perform(get(PATH + "/tasks").param("aiOnly", "true").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].errorCode").value("AI_REJECTED"));
    }

    @Test
    void logValidatesCursorsAndRequiresAdmin() throws Exception {
        mvc.perform(get(PATH + "/tasks").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.nextId").isEmpty());
        mvc.perform(get(PATH + "/tasks").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/tasks").with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/tasks").param("beforeId", "a".repeat(64)).with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/tasks").param("beforeAt", "2026-09-23T00:00:00Z").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/tasks").param("beforeAt", "2026-09-23T00:00:00Z").param("beforeId", "invalid")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }

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

    @Test
    void failureLogFiltersBeforePagingAndHandlesMissingReasonAndRule() throws Exception {
        var rule = UUID.randomUUID();
        for (int i = 1; i <= 30; i++) image(i, 53);
        jdbc.update("UPDATE ai_review_task SET status='FAILED',issued_rule_id=?,last_error_code='AI_REJECTED'", rule);
        jdbc.update("UPDATE ai_review_task SET last_error_code='DELIVERY_UNAVAILABLE' WHERE image_id=?", "%064x".formatted(1));
        jdbc.update("UPDATE ai_review_task SET last_error_code=NULL,issued_rule_id=NULL WHERE image_id=?", "%064x".formatted(2));
        mvc.perform(get(PATH + "/tasks").param("aiErrorCode", "DELIVERY_UNAVAILABLE").param("issuedRuleId", rule.toString())
                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imageId").value("%064x".formatted(1)));
        mvc.perform(get(PATH + "/tasks").param("aiErrorMissing", "true").param("issuedRuleMissing", "true")
                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imageId").value("%064x".formatted(2)));
        mvc.perform(get(PATH + "/tasks").param("aiErrorCode", "' OR TRUE --").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(PATH + "/tasks").param("issuedRuleId", "invalid").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
