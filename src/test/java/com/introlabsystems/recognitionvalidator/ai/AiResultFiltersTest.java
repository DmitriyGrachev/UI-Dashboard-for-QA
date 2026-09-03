package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.scheduler.RetentionCleanupService;
import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AiResultFiltersTest extends AiTestSupport {
    @Autowired MockMvc mvc;
    @Autowired RetentionCleanupService cleanup;
    @Autowired ObjectMapper json;

    @Test
    void completedAiPagesKeepTieBreakCursorAndOperatorClaimsOldestIndependently() throws Exception {
        String oldest = image(1, 53);
        String middle = image(2, 53);
        String newest = image(3, 53);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',valid=true,verdict='MATCH',certainty=97,checked_at=now()");
        // Equal timestamps still have an unambiguous order by image ID.
        jdbc.update("UPDATE ai_review_task SET file_created_at='2026-08-30T00:00:00Z'");
        jdbc.update("UPDATE review_task SET file_created_at='2026-08-30T00:00:00Z'");
        var first = mvc.perform(get("/admin/api/screenshots").param("aiResult", "MATCHED")
                        .param("limit", "1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].imageId").value(newest)).andReturn();
        var cursor = json.readTree(first.getResponse().getContentAsString());
        mvc.perform(get("/admin/api/screenshots").param("aiResult", "MATCHED").param("limit", "1")
                        .param("cursorCreatedAt", cursor.get("nextCreatedAt").asText())
                        .param("cursorId", cursor.get("nextId").asText()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].imageId").value(middle));
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,enabled,created_at) VALUES (?,'ai-page-op','hash',true,now())", operator);
        mvc.perform(post("/api/review-tasks/claim").with(user(new OperatorPrincipal(operator, "ai-page-op", "hash", true)))
                        .with(csrf()).contentType("application/json")
                        .content("{\"filters\":{\"aiResult\":\"MATCHED\",\"certaintyFrom\":90,\"tokenId\":53,\"sessionId\":\"session-a\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.imageId").value(oldest));
        assertThat(jdbc.queryForObject("SELECT status FROM ai_review_task WHERE image_id=?", String.class, oldest)).isEqualTo("COMPLETED");
    }

    @Test
    void adminAndOperatorSummariesUseSameIndependentAiFilters() throws Exception {
        String matched = image(1, 53);
        String unmatched = image(2, 53);
        image(3, 53);
        String absent = image(4, 53);
        jdbc.update("DELETE FROM ai_review_task WHERE image_id=?", absent);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',valid=true,verdict='MATCH',certainty=97,checked_at=now() WHERE image_id=?", matched);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',valid=false,verdict='MISMATCH',checked_at=now() WHERE image_id=?", unmatched);
        mvc.perform(get("/admin/api/screenshots").param("aiResult", "MATCHED").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].imageId").value(matched));
        mvc.perform(get("/admin/api/screenshots/summary").param("aiResult", "UNCHECKED").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(get("/admin/api/screenshots/summary").param("certaintyFrom", "0").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(get("/admin/api/screenshots/summary").param("aiResult", "UNCHECKED").param("certaintyFrom", "0").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,enabled,created_at) VALUES (?,'ai-filter-op','hash',true,now())", operator);
        mvc.perform(post("/api/review-tasks/summary").with(user(new OperatorPrincipal(operator, "ai-filter-op", "hash", true)))
                        .with(csrf()).contentType("application/json").content("{\"aiResult\":\"UNMATCHED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(1));
        mvc.perform(get("/admin/api/screenshots/" + matched).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ai.certainty").value(97));
    }

    @Test
    void retentionProtectsActiveOrConcurrentlyClaimedAiRows() {
        String id = image(1, 53);
        jdbc.update("UPDATE image_asset SET file_created_at=now()-interval '40 days' WHERE id=?", id);
        jdbc.update("UPDATE ai_review_task SET status='PROCESSING',lease_expires_at=now()+interval '2 minutes' WHERE image_id=?", id);
        assertThat(cleanup.runOnce()).isZero();
        jdbc.update("UPDATE ai_review_task SET status='PENDING',lease_expires_at=NULL WHERE image_id=?", id);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT image_id FROM ai_review_task WHERE image_id=? FOR UPDATE", String.class, id);
            try (var pool = Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(() -> cleanup.runOnce()).get(3, TimeUnit.SECONDS)).isZero();
            } catch (Exception e) { throw new AssertionError(e); }
        });
        assertThat(cleanup.runOnce()).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_review_task", Long.class)).isZero();
    }
}
