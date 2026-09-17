package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.introlabsystems.recognitionvalidator.ai.AiSettingsRepositoryTest.rule;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiTaskRepositoryTest extends AiTestSupport {
    @Autowired AiTaskRepository tasks;

    AiSettings all() { return new AiSettings(0, true, List.of(rule(1, null))); }

    @Test
    void fillsByPriorityThenAgeWithoutDuplicatesAcrossOverlappingRules() {
        String old = image(1, 1);
        String priority = image(2, 53);
        AiSettings rules = new AiSettings(0, true, List.of(rule(2, null), rule(1, 53L)));
        assertThat(tasks.claim(rules, 5)).extracting(AiClaim::imageId).containsExactly(priority, old);
        assertThat(tasks.claim(rules, 5)).isEmpty();
        assertThat(jdbc.queryForList("SELECT status FROM review_task", String.class)).containsOnly("PENDING");
    }

    @Test
    void concurrentThreadsClaimDistinctImagesWithOneSharedConfiguration() throws Exception {
        for (int i = 1; i <= 20; i++) image(i, 53);
        var configuration = all();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<AiClaim>>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) futures.add(pool.submit(() -> { start.await(); return tasks.claim(configuration, 4); }));
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (var future : futures) ids.addAll(future.get(5, TimeUnit.SECONDS).stream().map(AiClaim::imageId).toList());
            assertThat(ids).hasSize(20).doesNotHaveDuplicates();
        }
    }

    @Test
    void operatorLockDoesNotBlockAiOwnership() {
        String id = image(1, 53);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT image_id FROM review_task WHERE image_id=? FOR UPDATE", String.class, id);
            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(() -> tasks.claim(all(), 1)).get(2, TimeUnit.SECONDS))
                        .extracting(AiClaim::imageId).containsExactly(id);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test
    void resultIsIdempotentButCannotOverwriteOrCompleteExpiredLease() {
        String id = image(1, 53);
        AiClaim first = tasks.claim(all(), 1).getFirst();
        jdbc.update("UPDATE ai_review_task SET lease_expires_at=now()-interval '1 second' WHERE image_id=?", id);
        AiResult old = new AiResult(first.claimId(), true, "MATCH", 97, null, "ok");
        assertThatThrownBy(() -> tasks.complete(id, old)).hasMessageContaining("STALE_CLAIM");
        AiClaim second = tasks.claim(all(), 1).getFirst();
        assertThat(second.claimId()).isNotEqualTo(first.claimId());
        assertThatThrownBy(() -> tasks.complete(id, old)).hasMessageContaining("STALE_CLAIM");
        AiResult valid = new AiResult(second.claimId(), true, "MATCH", 97, null, "ok");
        tasks.complete(id, valid);
        var checkedAt = jdbc.queryForObject("SELECT checked_at FROM ai_review_task WHERE image_id=?", java.sql.Timestamp.class, id);
        jdbc.update("UPDATE ai_review_task SET lease_expires_at=now()-interval '1 second' WHERE image_id=?", id);
        tasks.complete(id, valid);
        assertThat(jdbc.queryForObject("SELECT checked_at FROM ai_review_task WHERE image_id=?", java.sql.Timestamp.class, id)).isEqualTo(checkedAt);
        assertThatThrownBy(() -> tasks.complete(id, new AiResult(second.claimId(), false, "MISMATCH", 90, null, null)))
                .hasMessageContaining("RESULT_CONFLICT");
        assertThat(tasks.claim(all(), 1)).isEmpty();
    }

    @Test
    void acceptedResultCountsOnceAndSurvivesImageDeletion() {
        String id = image(1, 53);
        AiClaim claim = tasks.claim(all(), 1).getFirst();
        AiResult result = new AiResult(claim.claimId(), true, "MATCH", 97, null, "ok");

        tasks.complete(id, result);
        tasks.complete(id, result);
        jdbc.update("DELETE FROM image_asset WHERE id = ?", id);

        assertThat(jdbc.queryForMap("SELECT total_checked, matched_count, not_matched_count "
                        + "FROM ai_daily_statistics").values())
                .containsExactlyInAnyOrder(1L, 1L, 0L);
    }

    @Test
    void noFallbackAndNoCloudOnlyWhenB2Disabled() {
        String id = image(1, 53);
        assertThat(tasks.claim(new AiSettings(0, false, List.of()), 1)).isEmpty();
        assertThat(tasks.claim(new AiSettings(0, true, List.of(rule(1, 999L))), 1)).isEmpty();
        jdbc.update("UPDATE image_asset SET file_available=false, cloud_object_key='test.png', cloud_uploaded_at=now() WHERE id=?", id);
        assertThat(tasks.claim(all(), 1)).isEmpty();
    }

    @Test
    void concurrentClaimsWithDifferentRuleOrdersKeepOwnershipAndAvoidActivityDeadlocks() throws Exception {
        var first = rule(1, 53L);
        var second = rule(2, 7L);
        var fallback = rule(3, null);
        var normal = new AiSettings(0, true, List.of(first, second, fallback));
        var reversed = new AiSettings(1, true, List.of(
                new AiRule(second.id(), second.name(), true, 1, second.gameCode(), null, null, 7L, null, null),
                new AiRule(first.id(), first.name(), true, 2, first.gameCode(), null, null, 53L, null, null), fallback));
        for (int i = 1; i <= 60; i++) image(i, i % 3 == 0 ? 53 : i % 3 == 1 ? 7 : 999);
        var gate = new CountDownLatch(1);
        List<String> issued = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<List<AiClaim>>>();
            for (int i = 0; i < 6; i++) {
                var config = i % 2 == 0 ? normal : reversed;
                futures.add(pool.submit(() -> { gate.await(); return tasks.claim(config, 15); }));
            }
            gate.countDown();
            for (var future : futures) issued.addAll(future.get(10, TimeUnit.SECONDS).stream().map(AiClaim::imageId).toList());
        }
        for (int i = 0; i < 3; i++) issued.addAll(tasks.claim(normal, 20).stream().map(AiClaim::imageId).toList());
        assertThat(issued).hasSize(60).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ai_review_task WHERE issued_rule_id <>
                  CASE WHEN token_id=53 THEN ?::uuid WHEN token_id=7 THEN ?::uuid ELSE ?::uuid END
                """, Long.class, first.id(), second.id(), fallback.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_rule_activity", Long.class)).isEqualTo(3);
    }

    @Test
    void expiredClaimsRecoverConcurrentlyAndOldResultsCannotOverwriteTheirNewOwners() throws Exception {
        var config = all();
        for (int i = 1; i <= 40; i++) image(i, 53);
        var old = new ArrayList<>(tasks.claim(config, 20));
        old.addAll(tasks.claim(config, 20));
        jdbc.update("UPDATE ai_review_task SET lease_expires_at=now()-interval '1 minute'");
        var gate = new CountDownLatch(1);
        List<AiClaim> renewed = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<List<AiClaim>>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { gate.await(); return tasks.claim(config, 10); }));
            gate.countDown();
            for (var future : futures) renewed.addAll(future.get(10, TimeUnit.SECONDS));
        }
        for (int i = 0; i < 2; i++) renewed.addAll(tasks.claim(config, 20));
        assertThat(renewed).extracting(AiClaim::imageId).hasSize(40).doesNotHaveDuplicates();
        var stale = old.getFirst();
        assertThatThrownBy(() -> tasks.complete(stale.imageId(), new AiResult(stale.claimId(), true, "MATCH", 99, 99, "old")))
                .hasMessageContaining("STALE_CLAIM");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_review_task WHERE status='PROCESSING' AND attempt_count=2", Long.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT last_result_at FROM ai_rule_activity WHERE rule_id=?", java.sql.Timestamp.class,
                config.rules().getFirst().id())).isNull();
    }

    @Test
    void claimsConfiguredGameAndReturnsItsExactCode() {
        String id = image(1, 53);
        jdbc.update("UPDATE image_asset SET game_code='bj_igt' WHERE id=?", id);
        jdbc.update("UPDATE review_task SET game_code='bj_igt' WHERE image_id=?", id);
        jdbc.update("UPDATE ai_review_task SET game_code='bj_igt' WHERE image_id=?", id);

        AiClaim claim = tasks.claim(new AiSettings(0, true, List.of(rule(1, null, "bj_igt"))), 1).getFirst();

        assertThat(claim.gameCode()).isEqualTo("bj_igt");
        assertThat(jdbc.queryForObject("SELECT game FROM ai_review_task WHERE image_id=?", String.class, id))
                .isEqualTo("bj_igt");
    }

    @Test
    void claimsWithB2RetentionEnabled() {
        String id = image(1, 53);
        AiTaskRepository b2Tasks = new AiTaskRepository(
                new NamedParameterJdbcTemplate(jdbc),
                transactionManager,
                new AiQueueProperties(Duration.ofMinutes(2)),
                b2Enabled(),
                mock(DailyStatisticsRepository.class),
                mock(ReviewDisagreementRepository.class),
                new com.introlabsystems.recognitionvalidator.ai.repository.AiRuleActivityRepository(new NamedParameterJdbcTemplate(jdbc))
        );

        assertThat(b2Tasks.hasEligiblePending(all(), b2Tasks.databaseNow())).isTrue();
        assertThat(b2Tasks.claim(all(), 1))
                .extracting(AiClaim::imageId)
                .containsExactly(id);
    }

    private static B2StorageProperties b2Enabled() {
        return new B2StorageProperties(
                true, URI.create("https://s3.eu-central-003.backblazeb2.com"),
                "bucket", "access", "secret", "validator/", 10, 1,
                Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofDays(3),
                Duration.ofMinutes(30), Duration.ofDays(21), Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(45), Duration.ofMinutes(2), 4
        );
    }
}
