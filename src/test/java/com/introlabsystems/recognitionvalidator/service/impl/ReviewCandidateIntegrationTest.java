package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewCandidateBuffer;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewCandidateIntegrationTest extends AbstractReviewIntegrationTest {
    @Autowired ReviewClaimRepository claims;
    @Autowired ReviewCandidateBuffer buffer;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean NamedParameterJdbcTemplate observedJdbc;
    @MockitoSpyBean java.time.Clock bufferClock;
    private final Instant now = Instant.parse("2026-09-14T10:00:00Z");
    @BeforeEach void resetBuffer() { buffer.clear(); doReturn(now).when(bufferClock).instant(); }

    @Test
    void lookaheadDoesNotReserveAnotherAssignmentAndMayBeClaimedByAnotherOperator() {
        var first = insertOperator("preload-first");
        var second = insertOperator("preload-second");
        String current = insertImage(1, now, "bj_igt", "a", false, true);
        String next = insertImage(2, now.plusSeconds(1), "bj_igt", "a", false, true);
        String last = insertImage(3, now.plusSeconds(2), "bj_igt", "a", false, true);
        var result = claims.claim(first, session("a"), now, Duration.ofMinutes(30), false, false);
        var response = com.introlabsystems.recognitionvalidator.dto.response.ReviewQueueResponse.from(result);
        assertThat(response.item().imageId()).isEqualTo(current);
        assertThat(response.nextImageId()).isEqualTo(next);
        assertThat(response.nextImageUrl()).isEqualTo("/api/images/" + next + "/content");
        assertThat(jdbc.queryForMap("SELECT status,assigned_to,lease_expires_at FROM review_task WHERE image_id=?", next))
                .containsEntry("status", "PENDING").containsEntry("assigned_to", null).containsEntry("lease_expires_at", null);
        assertThat(claims.claim(second, session("a"), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo(next);
        completeReview(current, first, com.introlabsystems.recognitionvalidator.model.enums.Decision.ACCEPTED, now);
        var actual = claims.claim(first, session("a"), now, Duration.ofMinutes(30), false, false);
        assertThat(actual.item().orElseThrow().imageId()).isEqualTo(last);
        assertThat(actual.nextImageId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE status='ASSIGNED' AND assigned_to=?", Long.class, first)).isOne();
    }

    @Test
    void lookaheadUsesOnlyTheSelectedFilterAndExistingUnexpiredBuffer() {
        var operator = insertOperator("preload-filter");
        insertImage(1, now, "bj_igt", "a", false, true);
        String nextA = insertImage(2, now.plusSeconds(1), "bj_igt", "a", false, true);
        String firstB = insertImage(3, now.plusSeconds(2), "bj_igt", "b", false, true);
        String nextB = insertImage(4, now.plusSeconds(3), "bj_igt", "b", false, true);
        var initial = claims.claim(operator, session("a"), now, Duration.ofMinutes(30), false, false);
        assertThat(initial.nextImageId()).isEqualTo(nextA);
        var existing = claims.claim(operator, session("a"), now, Duration.ofMinutes(30), false, false);
        assertThat(existing.item()).isEqualTo(initial.item());
        assertThat(existing.nextImageId()).isEqualTo(nextA);
        doReturn(now.plusSeconds(5)).when(bufferClock).instant();
        assertThat(claims.claim(operator, session("a"), now, Duration.ofMinutes(30), false, false).nextImageId()).isNull();
        var replaced = claims.claim(operator, session("b"), now, Duration.ofMinutes(30), true, false);
        assertThat(replaced.item().orElseThrow().imageId()).isEqualTo(firstB);
        assertThat(replaced.nextImageId()).isEqualTo(nextB);
        var empty = claims.claim(operator, session("empty"), now, Duration.ofMinutes(30), true, false);
        assertThat(empty.item()).isEmpty();
        assertThat(empty.nextImageId()).isNull();
    }

    @Test
    void thirtyDatabaseAssignmentsUseOneFullCandidateSearch() {
        for (int i = 1; i <= 30; i++) insertImage(i, now.plusSeconds(i), "bj_igt", "session", false, true);
        for (int i = 1; i <= 30; i++) {
            var operator = insertOperator("operator-" + i);
            assertThat(claims.claim(operator, ReviewFilters.none(), now, Duration.ofMinutes(30), false, false)
                    .item().orElseThrow().imageId()).isEqualTo("%064x".formatted(i));
        }
        verify(observedJdbc, times(1)).query(argThat(sql -> sql.contains("LIMIT 30")),
                any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<String>>any());
        verify(observedJdbc, times(30)).query(argThat(sql -> sql.contains("IN (:candidateIds)")),
                any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<String>>any());
    }

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

    @Test
    void distinctFiltersReuseTheirOwnBatchesWithoutMixingImages() {
        var filters = List.of(session("a"), session("b"));
        for (int i = 1; i <= 60; i++) insertImage(i, now.plusSeconds(i), "bj_igt", i % 2 == 0 ? "a" : "b", false, true);
        for (int i = 0; i < 60; i++) {
            var filter = filters.get(i % 2);
            var item = claims.claim(insertOperator("distinct-" + i), filter,
                    now, Duration.ofMinutes(30), false, false).item().orElseThrow();
            assertThat(item.sessionId()).isEqualTo(filter.sessionId());
        }
        verify(observedJdbc, times(2)).query(argThat(sql -> sql.contains("LIMIT 30")),
                any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<String>>any());
        verify(observedJdbc, times(60)).query(argThat(sql -> sql.contains("IN (:candidateIds)")),
                any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<String>>any());
    }

    @Test
    void concurrentOverlappingFiltersRevalidateSharedIdsAndKeepIndependentCounts() throws Exception {
        var filters = List.of(ReviewFilters.none(), session("a"), session("b"));
        for (int i = 1; i <= 32; i++) insertImage(i, now.plusSeconds(i), "bj_igt", i % 2 == 0 ? "a" : "b", false, true);
        var assigned = new java.util.HashSet<String>();
        // Real claims warm overlapping buffers; subsequent claims encounter each other's stale hints.
        for (int i = 0; i < filters.size(); i++) assigned.add(claims.claim(insertOperator("warm-" + i), filters.get(i),
                now, Duration.ofMinutes(30), false, false).item().orElseThrow().imageId());
        var operators = IntStream.range(0, 8).mapToObj(i -> insertOperator("overlap-" + i)).toList();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                start.await();
                var filter = filters.get(i % filters.size());
                var item = claims.claim(operators.get(i), filter, now, Duration.ofMinutes(30), false, false).item().orElseThrow();
                if (filter.sessionId() != null) assertThat(item.sessionId()).isEqualTo(filter.sessionId());
                return item.imageId();
            })).toList();
            start.countDown();
            for (var future : futures) assertThat(assigned.add(future.get(10, TimeUnit.SECONDS))).isTrue();
        }
        assertThat(assigned).hasSize(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE status='PENDING'", Long.class)).isEqualTo(21);
        assertThat(claims.summarize(null, filters.get(0)).remaining()).isEqualTo(32);
        assertThat(claims.summarize(null, filters.get(1)).remaining()).isEqualTo(16);
        assertThat(claims.summarize(null, filters.get(2)).remaining()).isEqualTo(16);
        String completed = jdbc.queryForObject("SELECT image_id FROM review_task WHERE status='ASSIGNED' AND session_id='a' LIMIT 1", String.class);
        var owner = jdbc.queryForObject("SELECT assigned_to FROM review_task WHERE image_id=?", java.util.UUID.class, completed);
        completeReview(completed, owner, com.introlabsystems.recognitionvalidator.model.enums.Decision.ACCEPTED, now);
        assertThat(claims.summarize(null, filters.get(0)).remaining()).isEqualTo(31);
        assertThat(claims.summarize(null, filters.get(1)).remaining()).isEqualTo(15);
        assertThat(claims.summarize(null, filters.get(2)).remaining()).isEqualTo(16);
    }

    private static ReviewFilters session(String value) {
        return new ReviewFilters(null, null, null, value, null, null, null);
    }

    @Test
    void newOlderImageEntersTheNextBatchAtExpiryEvenWhenCachedCandidatesRemain() {
        for (int i = 1; i <= 3; i++) insertImage(i, now.plusSeconds(i), "bj_igt", "a", false, true);
        assertThat(claims.claim(insertOperator("first"), session("a"), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo("%064x".formatted(1));
        String arrived = insertImage(4, now.minusSeconds(1), "bj_igt", "a", false, true);
        doReturn(now.plusMillis(4999)).when(bufferClock).instant();
        assertThat(claims.claim(insertOperator("before-expiry"), session("a"), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo("%064x".formatted(2));
        doReturn(now.plusSeconds(5)).when(bufferClock).instant();
        assertThat(claims.claim(insertOperator("after-expiry"), session("a"), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo(arrived);
    }

    @Test
    void anEmptyQueueImmediatelySeesNewWorkWithoutWaitingForExpiry() {
        var operator = insertOperator("new-work");
        assertThat(claims.claim(operator, session("a"), now, Duration.ofMinutes(30), false, false).item()).isEmpty();
        String arrived = insertImage(1, now, "bj_igt", "a", false, true);
        assertThat(claims.claim(operator, session("a"), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo(arrived);
    }

    @Test
    void deletedAndChangedCandidatesCannotLeakThroughTheOriginalFilter() {
        for (int i = 1; i <= 4; i++) insertImage(i, now.plusSeconds(i), "bj_igt", "a", false, true);
        claims.claim(insertOperator("warm"), session("a"), now, Duration.ofMinutes(30), false, false);
        String changed = "%064x".formatted(2);
        jdbc.update("UPDATE image_asset SET session_id='b' WHERE id=?", changed);
        jdbc.update("UPDATE review_task SET session_id='b' WHERE image_id=?", changed);
        jdbc.update("DELETE FROM image_asset WHERE id=?", "%064x".formatted(3));
        assertThat(claims.claim(insertOperator("a"), session("a"), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo("%064x".formatted(4));
        assertThat(claims.claim(insertOperator("b"), session("b"), now, Duration.ofMinutes(30), false, false)
                .item().orElseThrow().imageId()).isEqualTo(changed);
    }
}
