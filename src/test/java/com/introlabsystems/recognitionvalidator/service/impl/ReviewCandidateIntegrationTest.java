package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewCandidateBuffer;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewCandidateIntegrationTest extends AbstractReviewIntegrationTest {
    @Autowired ReviewClaimRepository claims;
    @Autowired ReviewCandidateBuffer buffer;
    @Autowired PlatformTransactionManager transactions;
    private final Instant now = Instant.parse("2026-09-14T10:00:00Z");
    @BeforeEach void resetBuffer() { buffer.clear(); }

    @Test
    void staleCandidatesAreRecheckedAndFallbackFindsNewWork() {
        var first = insertOperator("first");
        var second = insertOperator("second");
        String original = insertImage(1, now, "bj_igt", "session", false, true);
        String stale = insertImage(2, now.plusSeconds(1), "bj_igt", "session", false, true);
        assertThat(claims.claim(first, ReviewFilters.none(), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE image_id=?", String.class, stale)).isEqualTo("PENDING");
        jdbc.update("UPDATE image_asset SET file_available=false WHERE id=?", stale);
        String fresh = insertImage(3, now.plusSeconds(2), "bj_igt", "session", false, true);
        assertThat(claims.claim(second, ReviewFilters.none(), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo(fresh);
    }

    @Test
    void concurrentOperatorsNeverReceiveTheSameCachedCandidate() throws Exception {
        var operators = IntStream.range(0, 8).mapToObj(i -> insertOperator("operator-" + i)).toList();
        for (int i = 1; i <= 30; i++) insertImage(i, now.plusSeconds(i), "bj_igt", "session", false, true);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = operators.stream().map(id -> pool.submit(() -> claims.claim(id, ReviewFilters.none(),
                    now, Duration.ofMinutes(30), false, false).item().orElseThrow().imageId())).toList();
            var ids = new java.util.HashSet<String>();
            for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(8);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE status='PENDING'", Long.class)).isEqualTo(22);
    }

    @Test
    void rolledBackAssignmentRemainsClaimable() {
        var first = insertOperator("first");
        String image = insertImage(1, now, "bj_igt", "session", false, true);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            claims.claim(first, ReviewFilters.none(), now, Duration.ofMinutes(30), false, false);
            tx.setRollbackOnly();
        });
        assertThat(claims.claim(first, ReviewFilters.none(), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo(image);
    }
}
