package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.dto.AiClaim;
import com.introlabsystems.recognitionvalidator.ai.dto.AiResult;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.service.impl.DecisionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.introlabsystems.recognitionvalidator.ai.AiSettingsRepositoryTest.rule;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewDisagreementRepositoryTest extends AiTestSupport {

    private final AiSettings all = new AiSettings(0, true, List.of(rule(10, null)));

    @Autowired AiTaskRepository tasks;
    @Autowired DecisionService decisions;
    @Autowired ReviewDisagreementRepository disagreements;
    @Autowired DataSource dataSource;

    @Test
    void capturesWhenAiCompletesBeforeOperator() {
        String id = image(1, 53);
        AiClaim claim = tasks.claim(all, 1).getFirst();
        tasks.complete(id, new AiResult(claim.claimId(), true, "MATCH", 99, null, null));
        decide(id, Decision.REJECTED);
        tasks.complete(id, new AiResult(claim.claimId(), true, "MATCH", 99, null, null));

        assertThat(disagreementCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ai_matched FROM review_disagreement WHERE image_id=?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void capturesWhenOperatorCompletesBeforeAi() {
        String id = image(2, 53);
        decide(id, Decision.ACCEPTED);
        AiClaim claim = tasks.claim(all, 1).getFirst();
        tasks.complete(id, new AiResult(claim.claimId(), false, "MISMATCH", 99, null, null));

        assertThat(disagreementCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ai_matched FROM review_disagreement WHERE image_id=?", Boolean.class, id))
                .isFalse();
    }

    @Test
    void excludesMatchingAndOneSidedResults() {
        String matching = image(3, 53);
        String oneSidedAi = image(4, 53);
        String oneSidedOperator = image(5, 53);

        AiClaim matchingClaim = tasks.claim(all, 1).getFirst();
        tasks.complete(matching, new AiResult(matchingClaim.claimId(), true, "MATCH", 99, null, null));
        decide(matching, Decision.ACCEPTED);

        AiClaim oneSidedClaim = tasks.claim(all, 1).getFirst();
        tasks.complete(oneSidedAi, new AiResult(oneSidedClaim.claimId(), true, "MATCH", 99, null, null));
        decide(oneSidedOperator, Decision.REJECTED);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_disagreement", Long.class)).isZero();
    }

    @Test
    void backfillIsIdempotentUsesLatestUtcTimestampAndSurvivesImageDeletion() {
        String id = image(6, 53);
        Instant checkedAt = Instant.parse("2026-08-31T23:59:00Z");
        Instant reviewedAt = Instant.parse("2026-09-01T00:01:00Z");
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED', valid=TRUE, checked_at=? WHERE image_id=?",
                Timestamp.from(checkedAt), id);
        jdbc.update("UPDATE review_task SET status='COMPLETED', decision='REJECTED', reviewed_at=? WHERE image_id=?",
                Timestamp.from(reviewedAt), id);

        assertThat(disagreements.backfill()).isEqualTo(1);
        assertThat(disagreements.backfill()).isZero();
        assertThat(jdbc.queryForObject("SELECT observed_at FROM review_disagreement WHERE image_id=?", Timestamp.class, id)
                .toInstant()).isEqualTo(reviewedAt);

        jdbc.update("DELETE FROM image_asset WHERE id=?", id);
        assertThat(disagreementCount(id)).isEqualTo(1);
    }

    @Test
    void concurrentCompletionsSerializeBeforeSourceWritesAndCaptureOnce() throws Exception {
        String id = image(7, 53);
        UUID operator = insertOperator("comparison-race");
        assign(id, operator);
        AiClaim claim = tasks.claim(all, 1).getFirst();
        AiResult result = new AiResult(claim.claimId(), true, "MATCH", 99, null, null);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "review-comparison:" + id);
                statement.execute();
            }

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            boolean released = false;
            try {
                Future<?> ai = pool.submit(() -> {
                    await(start);
                    entered.countDown();
                    tasks.complete(id, result);
                });
                Future<?> operatorFuture = pool.submit(() -> {
                    await(start);
                    entered.countDown();
                    decisions.decide(id, operator, Decision.REJECTED);
                });
                start.countDown();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("SELECT status FROM ai_review_task WHERE image_id=?", String.class, id))
                        .isEqualTo("PROCESSING");
                assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE image_id=?", String.class, id))
                        .isEqualTo("ASSIGNED");
                holder.commit();
                released = true;
                ai.get(5, TimeUnit.SECONDS);
                operatorFuture.get(5, TimeUnit.SECONDS);
            } finally {
                if (!released) {
                    holder.rollback();
                }
                pool.shutdownNow();
            }
        }

        assertThat(disagreementCount(id)).isEqualTo(1);
    }

    private void decide(String imageId, Decision decision) {
        UUID operator = insertOperator("operator-" + imageId.substring(60));
        assign(imageId, operator);
        decisions.decide(imageId, operator, decision);
    }

    private UUID insertOperator(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at)
                VALUES (?, ?, 'hash', TRUE, now())
                """, id, username);
        return id;
    }

    private void assign(String imageId, UUID operator) {
        jdbc.update("""
                UPDATE review_task
                SET status='ASSIGNED', assigned_to=?, assigned_at=now(), lease_expires_at=now()+interval '5 minutes'
                WHERE image_id=?
                """, operator, imageId);
    }

    private long disagreementCount(String imageId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review_disagreement WHERE image_id=?", Long.class, imageId);
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
