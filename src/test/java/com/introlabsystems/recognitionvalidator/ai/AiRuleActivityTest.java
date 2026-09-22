package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiRuleActivityTest extends AiTestSupport {
    @Autowired AiTaskRepository tasks;
    @Autowired AiSettingsRepository settings;
    @Autowired com.introlabsystems.recognitionvalidator.ai.repository.AiRuleActivityRepository activities;
    @Autowired com.introlabsystems.recognitionvalidator.ai.repository.AiOperationsRepository operations;

    @Test
    void activitySeparatesActiveExpiredUnknownAndDeletedRulesWhilePaused() throws Exception {
        var first = AiSettingsRepositoryTest.rule(1, 53L);
        var second = AiSettingsRepositoryTest.rule(2, null);
        var deleted = UUID.randomUUID();
        settings.save(new AiSettings(0, false, List.of(first, second)));
        var now = java.time.Instant.parse("2026-09-22T10:00:00Z");
        for (int i = 1; i <= 7; i++) image(i, 53);
        jdbc.update("UPDATE ai_review_task SET status='PROCESSING',issued_rule_id=?,lease_expires_at=?", first.id(), Timestamp.from(now.plusSeconds(60)));
        jdbc.update("UPDATE ai_review_task SET lease_expires_at=? WHERE image_id=?", Timestamp.from(now), "%064x".formatted(2));
        jdbc.update("UPDATE ai_review_task SET lease_expires_at=NULL WHERE image_id=?", "%064x".formatted(3));
        jdbc.update("UPDATE ai_review_task SET issued_rule_id=? WHERE image_id=?", second.id(), "%064x".formatted(4));
        jdbc.update("UPDATE ai_review_task SET issued_rule_id=? WHERE image_id=?", deleted, "%064x".formatted(5));
        jdbc.update("UPDATE ai_review_task SET issued_rule_id=NULL WHERE image_id=?", "%064x".formatted(6));
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED' WHERE image_id=?", "%064x".formatted(7));
        var rows = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(activities.read(now));
        assertThat(rows.size()).isEqualTo(4);
        var counts = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        rows.forEach(row -> counts.put(row.path("ruleId").asText(), row));
        assertThat(counts.get(first.id().toString()).path("processing").asLong(-1)).isEqualTo(3);
        assertThat(counts.get(first.id().toString()).path("active").asLong(-1)).isOne();
        assertThat(counts.get(first.id().toString()).path("expired").asLong(-1)).isOne();
        for (String id : List.of(second.id().toString(), deleted.toString(), "null")) {
            assertThat(counts.get(id).path("active").asLong(-1)).isOne();
        }
        assertThat(operations.activity(now).lastIssuedRuleIds()).isEmpty();
    }

    @Test
    void cursorMarksEveryRuleInTheSameBatchAndNeverInventsHistoricalActivity() {
        var first = AiSettingsRepositoryTest.rule(1, 53L);
        var second = AiSettingsRepositoryTest.rule(2, null);
        var saved = settings.save(new AiSettings(0, true, List.of(first, second)));
        var now = java.time.Instant.parse("2026-09-17T10:00:00Z");
        assertThat(operations.activity(now).lastIssuedRuleIds()).isEmpty();
        activities.issued(java.util.Map.of(first.id(), 2, second.id(), 3), now);
        assertThat(operations.activity(now).lastIssuedRuleIds()).containsExactly(first.id(), second.id());
        activities.issued(java.util.Map.of(second.id(), 1), now.plusNanos(1000));
        assertThat(operations.activity(now).lastIssuedRuleIds()).containsExactly(second.id());
        assertThat(operations.activity(now).revision()).isEqualTo(saved.revision());
    }

    @Test
    void snapshotShowsAllExpiredLeasesAndDisabledRulesWithoutChangingTasks() {
        var rule = AiSettingsRepositoryTest.rule(1, null);
        var saved = settings.save(new AiSettings(0, true, List.of(rule)));
        for (int i = 1; i <= 103; i++) image(i, 53);
        jdbc.update("UPDATE ai_review_task SET status='PROCESSING',issued_rule_id=?,lease_expires_at=now()-interval '1 hour'", rule.id());
        settings.save(new AiSettings(saved.revision(), false, List.of(rule)));
        var snapshot = activities.read(java.time.Instant.now());
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.getFirst().expired()).isEqualTo(103);
        assertThat(snapshot.getFirst().expiredImageId()).isEqualTo("%064x".formatted(1));
        assertThat(snapshot.getFirst().lastIssuedAt()).isNull(); // Historical issuance is unknown, never inferred from leases.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_review_task WHERE status='PROCESSING'", Long.class)).isEqualTo(103);
    }

    @Test
    void anOlderConcurrentBatchCannotMoveTheCursorBackwards() {
        var rule = UUID.randomUUID();
        var now = java.time.Instant.parse("2026-09-17T10:00:00Z");
        activities.issued(java.util.Map.of(rule, 3), now);
        activities.issued(java.util.Map.of(rule, 9), now.minusSeconds(10));
        assertThat(activity(rule, "last_issued_at").toInstant()).isEqualTo(now);
        assertThat(jdbc.queryForObject("SELECT last_issued_count FROM ai_rule_activity WHERE rule_id=?", Integer.class, rule)).isEqualTo(3);
    }

    @Test
    void recordsOnlyRealAssignmentsAndKeepsActivityAcrossSettingsEditsAndImageRetention() {
        var specific = AiSettingsRepositoryTest.rule(1, 53L);
        var fallback = AiSettingsRepositoryTest.rule(2, null);
        var saved = settings.save(new AiSettings(0, true, List.of(specific, fallback)));
        String first = image(1, 53);
        image(2, 7);
        var claims = tasks.claim(saved, 2);
        assertThat(activity(specific.id(), "last_issued_at")).isNotNull();
        assertThat(activity(fallback.id(), "last_issued_at")).isAfterOrEqualTo(activity(specific.id(), "last_issued_at"));
        assertThat(jdbc.queryForObject("SELECT last_issued_count FROM ai_rule_activity WHERE rule_id=?", Integer.class, specific.id())).isOne();
        var issued = activity(fallback.id(), "last_issued_at");
        assertThat(tasks.claim(saved, 2)).isEmpty();
        assertThat(activity(fallback.id(), "last_issued_at")).isEqualTo(issued);

        var result = new AiResult(claims.getFirst().claimId(), true, "MATCH", 99, 98, "ok");
        tasks.complete(first, result);
        var checked = activity(specific.id(), "last_result_at");
        tasks.complete(first, result);
        assertThat(activity(specific.id(), "last_result_at")).isEqualTo(checked).isNotNull();
        settings.save(new AiSettings(saved.revision(), false, List.of(fallback, specific)));
        jdbc.update("DELETE FROM image_asset WHERE id=?", first);
        assertThat(activity(specific.id(), "last_result_at")).isEqualTo(checked);
    }

    @Test
    void rejectedAndUndeliverableTasksKeepRuleAndImageDiagnosticsWithoutAcceptingAResult() {
        var rule = AiSettingsRepositoryTest.rule(1, null);
        var config = new AiSettings(0, true, List.of(rule));
        image(1, 53); image(2, 53);
        var claims = tasks.claim(config, 2);
        var rejected = new AiReject(claims.getFirst().imageId(), claims.getFirst().claimId(), "cannot read cards");
        tasks.reject(rejected);
        assertThat(jdbc.queryForMap("SELECT last_error_image_id,last_error_code,last_error_message FROM ai_rule_activity WHERE rule_id=?", rule.id()))
                .containsEntry("last_error_image_id", rejected.imageId()).containsEntry("last_error_code", "AI_REJECTED")
                .containsEntry("last_error_message", "cannot read cards");
        var errorAt = activity(rule.id(), "last_error_at");
        tasks.reject(rejected);
        assertThat(activity(rule.id(), "last_error_at")).isEqualTo(errorAt);
        tasks.preparationFailed(claims.getLast(), false, "DELIVERY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT last_error_image_id FROM ai_rule_activity WHERE rule_id=?", String.class, rule.id()))
                .isEqualTo(claims.getLast().imageId());
        assertThat(activity(rule.id(), "last_result_at")).isNull();
    }

    @Test
    void activityRollsBackWithTheAssignment() {
        var rule = AiSettingsRepositoryTest.rule(1, null);
        image(1, 53);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            tasks.claim(new AiSettings(0, true, List.of(rule)), 1);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_rule_activity WHERE rule_id=?", Long.class, rule.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_review_task", String.class)).isEqualTo("PENDING");
    }

    private Timestamp activity(UUID rule, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ai_rule_activity WHERE rule_id=?", Timestamp.class, rule);
    }
}
