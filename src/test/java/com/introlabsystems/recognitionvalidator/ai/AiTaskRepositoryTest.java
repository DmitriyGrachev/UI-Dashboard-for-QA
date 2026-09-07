package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.introlabsystems.recognitionvalidator.ai.AiSettingsRepositoryTest.rule;
import static org.assertj.core.api.Assertions.*;

class AiTaskRepositoryTest extends AiTestSupport {
    @Autowired AiTaskRepository tasks;

    AiSettings all() { return new AiSettings(0, true, List.of(rule(10, null))); }

    @Test
    void fillsByPriorityThenAgeWithoutDuplicatesAcrossOverlappingRules() {
        String old = image(1, 1);
        String priority = image(2, 53);
        AiSettings rules = new AiSettings(0, true, List.of(rule(20, null), rule(10, 53L)));
        assertThat(tasks.claim(rules, 5)).extracting(AiClaim::imageId).containsExactly(priority, old);
        assertThat(tasks.claim(rules, 5)).isEmpty();
        assertThat(jdbc.queryForList("SELECT status FROM review_task", String.class)).containsOnly("PENDING");
    }

    @Test
    void concurrentThreadsClaimDistinctImagesWithOneSharedConfiguration() throws Exception {
        for (int i = 1; i <= 20; i++) image(i, 53);
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<AiClaim>>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) futures.add(pool.submit(() -> { start.await(); return tasks.claim(all(), 4); }));
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
        assertThat(tasks.claim(new AiSettings(0, true, List.of(rule(10, 999L))), 1)).isEmpty();
        jdbc.update("UPDATE image_asset SET file_available=false, cloud_object_key='test.png', cloud_uploaded_at=now() WHERE id=?", id);
        assertThat(tasks.claim(all(), 1)).isEmpty();
    }
}
