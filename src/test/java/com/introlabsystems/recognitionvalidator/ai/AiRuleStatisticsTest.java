package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiRuleStatisticsTest extends AiTestSupport {
    @Autowired AiTaskRepository tasks;

    private AiRule rule(String name, int priority, boolean enabled, String game, Long token) {
        return new AiRule(UUID.randomUUID(), name, enabled, priority, game, null, null, token, null, null);
    }

    @Test
    void firstEnabledMatchOwnsEachTaskAndDefaultOnlyReceivesTheRemainder() {
        image(1, 53); image(2, 53); image(3, 7);
        String other = image(4, 53);
        jdbc.update("UPDATE ai_review_task SET game_code='bj_igt' WHERE image_id=?", other);
        AiRule disabled = rule("disabled", 1, false, "bj_single_deck_ags", null);
        AiRule first = rule("first", 2, true, "bj_single_deck_ags", 53L);
        AiRule overlap = rule("overlap", 3, true, "bj_single_deck_ags", 53L);
        AiRule fallback = rule("default", 4, true, "bj_single_deck_ags", null);
        AiRule otherGame = rule("other game", 5, true, "bj_igt", null);
        var settings = new AiSettings(0, true, List.of(disabled, first, overlap, fallback, otherGame));
        assertThat(tasks.ruleStatistics(settings, Instant.now())).extracting(AiRuleStatistics::remaining)
                .containsExactly(0L, 2L, 0L, 1L, 1L);
        assertThat(tasks.claim(settings, 10)).hasSize(4);
        assertThat(tasks.ruleStatistics(settings, Instant.now())).extracting(AiRuleStatistics::processing)
                .containsExactly(0L, 2L, 0L, 1L, 1L);
        assertThat(tasks.ruleStatistics(settings, Instant.now())).extracting(AiRuleStatistics::remaining)
                .containsOnly(0L);
    }

    @Test
    void movingDefaultBeforeSpecificRulesChangesOwnershipExactlyLikeClaim() {
        image(1, 53); image(2, 7);
        var fallback = rule("default", 1, true, "bj_single_deck_ags", null);
        var specific = rule("specific", 2, true, "bj_single_deck_ags", 53L);
        var settings = new AiSettings(0, true, List.of(specific, fallback));
        assertThat(tasks.ruleStatistics(settings, Instant.now())).extracting(AiRuleStatistics::remaining).containsExactly(2L, 0L);
        assertThat(tasks.claim(settings, 10)).hasSize(2);
        assertThat(jdbc.queryForList("SELECT issued_rule_id FROM ai_review_task", UUID.class)).containsOnly(fallback.id());
    }

    @Test
    void cloudAvailabilityHonorsRetentionAndKeysOnBothMetadataRows() {
        var b2 = new com.introlabsystems.recognitionvalidator.config.B2StorageProperties(
                true, java.net.URI.create("https://example.invalid"), "bucket", "access", "secret", "validator/", 10, 1,
                java.time.Duration.ofSeconds(10), java.time.Duration.ofMinutes(5), java.time.Duration.ofDays(3),
                java.time.Duration.ofMinutes(30), java.time.Duration.ofDays(21), java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(45), java.time.Duration.ofMinutes(2), 4);
        var cloudTasks = new AiTaskRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                transactionManager, new com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties(java.time.Duration.ofMinutes(2)),
                b2, org.mockito.Mockito.mock(com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository.class),
                org.mockito.Mockito.mock(com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository.class),
                new com.introlabsystems.recognitionvalidator.ai.repository.AiRuleActivityRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)));
        for (int i = 1; i <= 5; i++) image(i, 53);
        jdbc.update("UPDATE ai_review_task SET file_available=false, cloud_available_at=now()");
        jdbc.update("UPDATE image_asset SET file_available=false, cloud_object_key='cloud', cloud_uploaded_at=now()");
        jdbc.update("UPDATE image_asset SET cloud_object_key='  ' WHERE id=?", "%064x".formatted(2));
        jdbc.update("UPDATE image_asset SET cloud_uploaded_at=now()-interval '22 days' WHERE id=?", "%064x".formatted(3));
        jdbc.update("UPDATE ai_review_task SET cloud_available_at=now()-interval '22 days' WHERE image_id=?", "%064x".formatted(4));
        jdbc.update("UPDATE ai_review_task SET retry_after=now()+interval '1 hour' WHERE image_id=?", "%064x".formatted(5));
        var settings = new AiSettings(0, true, List.of(rule("default", 1, true, "bj_single_deck_ags", null)));
        assertThat(cloudTasks.ruleStatistics(settings, Instant.now()).getFirst().remaining()).isOne();
        assertThat(cloudTasks.claim(settings, 10)).extracting(AiClaim::imageId).containsExactly("%064x".formatted(1));
    }

    @Test
    void statusCountsKeepIssuedRuleOwnershipEvenAfterDisableOrConditionChange() {
        AiRule disabled = rule("disabled", 1, false, "unrelated-game", 999L);
        AiRule active = rule("default", 2, true, "bj_single_deck_ags", null);
        for (int i = 1; i <= 4; i++) image(i, 53);
        for (int i = 1; i <= 3; i++) jdbc.update("""
                UPDATE ai_review_task SET status=?, issued_rule_id=?, claim_id=?, lease_expires_at=now()+interval '1 hour',
                  valid=true, verdict='MATCH', certainty=90, checked_at=now() WHERE image_id=?
                """, List.of("PROCESSING", "COMPLETED", "FAILED").get(i - 1), disabled.id(), UUID.randomUUID(), "%064x".formatted(i));
        var settings = new AiSettings(0, true, List.of(disabled, active));
        assertThat(tasks.ruleStatistics(settings, Instant.now())).containsExactly(
                new AiRuleStatistics(disabled.id(), 0, 1, 1, 1), new AiRuleStatistics(active.id(), 1, 0, 0, 0));
        assertThat(tasks.ruleStatistics(new AiSettings(0, false, settings.rules()), Instant.now()))
                .containsExactly(new AiRuleStatistics(disabled.id(), 0, 1, 1, 1), new AiRuleStatistics(active.id(), 0, 0, 0, 0));
        assertThat(tasks.ruleStatistics(new AiSettings(0, false, List.of()), Instant.now())).isEmpty();
    }

    @Test
    void remainingUsesEveryClaimPredicateAndDoesNotMutateRows() {
        for (int i = 1; i <= 9; i++) image(i, 53);
        jdbc.update("UPDATE ai_review_task SET retry_after=now()+interval '1 hour' WHERE image_id=?", "%064x".formatted(2));
        jdbc.update("UPDATE ai_review_task SET file_available=false WHERE image_id=?", "%064x".formatted(3));
        jdbc.update("UPDATE image_asset SET file_available=false, cloud_object_key='cloud', cloud_uploaded_at=now() WHERE id=?", "%064x".formatted(4));
        jdbc.update("UPDATE ai_review_task SET session_id='other' WHERE image_id=?", "%064x".formatted(5));
        jdbc.update("UPDATE ai_review_task SET has_user_hand=false WHERE image_id=?", "%064x".formatted(6));
        jdbc.update("UPDATE ai_review_task SET token_id=7 WHERE image_id=?", "%064x".formatted(7));
        Instant start = Instant.parse("2026-08-30T00:00:01Z");
        var rule = new AiRule(null, "bounded", true, 1, "bj_single_deck_ags", start, start.plusSeconds(7), 53L, "session-a", true);
        var settings = new AiSettings(0, true, List.of(rule));
        var before = jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id");
        assertThat(tasks.ruleStatistics(settings, Instant.now()).getFirst().remaining()).isOne();
        assertThat(jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id")).isEqualTo(before);
        assertThat(tasks.claim(settings, 10)).extracting(AiClaim::imageId).containsExactly("%064x".formatted(1));
    }

    @Test
    void previewsTheSameBoundedExpiredLeaseRecoveryWithoutChangingStatus() {
        AiRule rule = rule("default", 1, true, "bj_single_deck_ags", null);
        for (int i = 1; i <= 103; i++) image(i, 53);
        jdbc.update("""
                UPDATE ai_review_task SET status='PROCESSING', issued_rule_id=?, claim_id=?,
                  lease_expires_at=now()-interval '1 hour', retry_after=now()+interval '1 day'
                """, rule.id(), UUID.randomUUID());
        var settings = new AiSettings(0, true, List.of(rule));
        assertThat(tasks.ruleStatistics(settings, Instant.now())).containsExactly(new AiRuleStatistics(rule.id(), 100, 103, 0, 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_review_task WHERE status='PENDING'", Long.class)).isZero();
        assertThat(tasks.claim(settings, 10)).hasSize(10);
    }
}
