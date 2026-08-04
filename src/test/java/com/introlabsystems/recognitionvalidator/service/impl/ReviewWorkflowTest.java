package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.exception.DecisionConflictException;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewItem;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;
import com.introlabsystems.recognitionvalidator.service.ReviewWorkflowService;
import com.introlabsystems.recognitionvalidator.service.ReviewQueueService;
import com.introlabsystems.recognitionvalidator.service.StatisticsService;
import com.introlabsystems.recognitionvalidator.model.value.OperatorStatistics;
import com.introlabsystems.recognitionvalidator.dao.jdbc.StatisticsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

class ReviewWorkflowTest extends AbstractReviewIntegrationTest {

    @MockitoSpyBean
    private ReviewQueueService queueService;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private ReviewWorkflowService workflowService;

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void hibernateCreatesValidatorTablesWithoutFlyway() {
        assertThat(tableExists("app_user")).isTrue();
        assertThat(tableExists("image_asset")).isTrue();
        assertThat(tableExists("review_task")).isTrue();
        assertThat(tableExists("operator_daily_statistics")).isTrue();
        assertThat(applicationContext.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("flyway"));
    }

    @Test
    void claimsOldestAvailableImage() {
        UUID operatorId = insertOperator("oldest");
        String newer = insertImage(2, Instant.parse("2026-07-30T11:00:00Z"),
                "bj_igt", "2_session", false, true);
        String older = insertImage(1, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "1_session", false, true);

        ReviewItem claimed = queueService.claim(operatorId, ReviewFilters.none()).orElseThrow();

        assertThat(claimed.imageId()).isEqualTo(older);
        assertThat(claimed.imageId()).isNotEqualTo(newer);
    }

    @Test
    void appliesDateSessionGameAndNotificationFilters() {
        UUID operatorId = insertOperator("filtered");
        Instant targetTime = Instant.parse("2026-07-30T10:00:00Z");
        String target = insertImage(10, targetTime,
                "bj_igt", "39_target-session", true, true);
        insertImage(11, targetTime.minus(2, ChronoUnit.DAYS),
                "bj_igt", "39_target-session", true, true);
        insertImage(12, targetTime,
                "bj_relax", "39_target-session", true, true);
        insertImage(13, targetTime,
                "bj_igt", "other-session", true, true);
        insertImage(14, targetTime,
                "bj_igt", "39_target-session", false, true);

        ReviewFilters filters = new ReviewFilters(
                targetTime.minus(1, ChronoUnit.HOURS),
                targetTime.plus(1, ChronoUnit.HOURS),
                "39_target-session",
                "bj_igt",
                true,
                null
        );

        assertThat(queueService.claim(operatorId, filters))
                .map(ReviewItem::imageId)
                .contains(target);
    }

    @Test
    void refreshReturnsCurrentAssignment() {
        UUID operatorId = insertOperator("refresh");
        insertImage(20, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "session", false, true);
        insertImage(21, Instant.parse("2026-07-30T11:00:00Z"),
                "bj_igt", "session", false, true);

        String first = queueService.claim(operatorId, ReviewFilters.none())
                .orElseThrow().imageId();
        String refreshed = queueService.claim(operatorId, ReviewFilters.none())
                .orElseThrow().imageId();

        assertThat(refreshed).isEqualTo(first);
    }

    @Test
    void replacingFiltersReleasesCurrentAssignmentAndCountsMatchingQueue() {
        UUID operatorId = insertOperator("replace-filter");
        String released = insertImage(22, Instant.parse("2026-07-30T09:00:00Z"),
                "bj_relax", "session", false, true);
        String expected = insertImage(23, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "session", false, true);
        insertImage(24, Instant.parse("2026-07-30T11:00:00Z"),
                "bj_igt", "session", false, true);
        queueService.claim(operatorId, ReviewFilters.none()).orElseThrow();

        ReviewQueueResult result = queueService.claim(
                operatorId,
                new ReviewFilters(null, null, null, "bj_igt", null, null),
                true,
                true
        );

        assertThat(result.item()).map(ReviewItem::imageId).contains(expected);
        assertThat(result.remaining()).isEqualTo(2L);
        assertThat(jdbc.queryForMap(
                "select status, assigned_to from review_task where image_id = ?",
                released
        )).containsEntry("status", "PENDING")
                .containsEntry("assigned_to", null);
    }

    @Test
    void reportsCountAndCreatedAtRangeForTheFilteredQueue() {
        UUID operatorId = insertOperator("queue-range");
        Instant oldest = Instant.parse("2026-08-03T02:00:00Z");
        Instant newest = Instant.parse("2026-08-03T05:00:00Z");
        insertImage(25, oldest, "bj_igt", "target-session", false, true);
        insertImage(26, newest, "bj_igt", "target-session", false, true);
        insertImage(27, oldest.minusSeconds(3600),
                "bj_relax", "target-session", false, true);

        ReviewQueueResult result = queueService.claim(
                operatorId,
                new ReviewFilters(null, null, null, "bj_igt", null, null),
                true,
                true
        );

        assertThat(result.remaining()).isEqualTo(2L);
        assertThat(result.oldestCreatedAt()).isEqualTo(oldest);
        assertThat(result.newestCreatedAt()).isEqualTo(newest);
    }

    @Test
    void concurrentOperatorsCannotClaimTheSameImage() throws Exception {
        UUID firstOperator = insertOperator("concurrent-1");
        UUID secondOperator = insertOperator("concurrent-2");
        String imageId = insertImage(30, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "session", false, true);

        List<Optional<ReviewItem>> results = claimConcurrently(firstOperator, secondOperator);

        assertThat(results.stream().flatMap(Optional::stream).map(ReviewItem::imageId))
                .containsExactly(imageId);
    }

    @Test
    void concurrentClaimsByOneOperatorReturnOneAssignment() throws Exception {
        UUID operatorId = insertOperator("same-operator");
        insertImage(40, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "session", false, true);
        insertImage(41, Instant.parse("2026-07-30T11:00:00Z"),
                "bj_igt", "session", false, true);

        List<Optional<ReviewItem>> results = claimConcurrently(operatorId, operatorId);

        assertThat(results).allMatch(Optional::isPresent);
        assertThat(results.stream().map(result -> result.orElseThrow().imageId()).distinct())
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from review_task where status = 'ASSIGNED'",
                Long.class
        )).isEqualTo(1L);
    }

    @Test
    void expiredLeaseReturnsImageToQueue() {
        UUID firstOperator = insertOperator("expired-1");
        UUID secondOperator = insertOperator("expired-2");
        String imageId = insertImage(50, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "session", false, true);
        queueService.claim(firstOperator, ReviewFilters.none()).orElseThrow();
        jdbc.update("""
                UPDATE review_task
                SET lease_expires_at = now() - interval '1 minute'
                WHERE image_id = ?
                """, imageId);

        ReviewItem reassigned = queueService.claim(secondOperator, ReviewFilters.none())
                .orElseThrow();

        assertThat(reassigned.imageId()).isEqualTo(imageId);
    }

    @Test
    void finalDecisionCannotBeRepeatedOrMadeByAnotherOperator() {
        UUID owner = insertOperator("decision-owner");
        UUID stranger = insertOperator("decision-stranger");
        String imageId = insertImage(60, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "session", false, true);
        queueService.claim(owner, ReviewFilters.none()).orElseThrow();

        decisionService.decide(imageId, owner, Decision.ACCEPTED);

        assertThat(jdbc.queryForObject(
                "select decision from review_task where image_id = ?",
                String.class,
                imageId
        )).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject(
                "select reviewed_at is not null from review_task where image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
        assertThatThrownBy(() -> decisionService.decide(imageId, owner, Decision.REJECTED))
                .isInstanceOf(DecisionConflictException.class);
        assertThatThrownBy(() -> decisionService.decide(imageId, stranger, Decision.REJECTED))
                .isInstanceOf(DecisionConflictException.class);
        assertThat(queueService.claim(stranger, ReviewFilters.none())).isEmpty();
    }

    @Test
    void firstRejectedDecisionIsStoredAndCompletesTask() {
        UUID operatorId = insertOperator("rejected");
        String imageId = insertImage(61, Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt", "session", false, true);
        queueService.claim(operatorId, ReviewFilters.none()).orElseThrow();

        decisionService.decide(imageId, operatorId, Decision.REJECTED);

        assertThat(jdbc.queryForObject(
                "select decision from review_task where image_id = ?",
                String.class,
                imageId
        )).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "select status from review_task where image_id = ?",
                String.class,
                imageId
        )).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select reviewed_at is not null from review_task where image_id = ?",
                Boolean.class,
                imageId
        )).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT total_checked, matched_count, not_matched_count
                FROM operator_daily_statistics
                WHERE operator_id = ?
                """, operatorId))
                .containsEntry("total_checked", 1L)
                .containsEntry("matched_count", 0L)
                .containsEntry("not_matched_count", 1L);

        assertThatThrownBy(() -> decisionService.decide(imageId, operatorId, Decision.ACCEPTED))
                .isInstanceOf(DecisionConflictException.class);
        assertThat(jdbc.queryForObject("""
                SELECT total_checked
                FROM operator_daily_statistics
                WHERE operator_id = ?
                """, Long.class, operatorId)).isEqualTo(1L);
    }

    @Test
    void decisionRemainsCommittedWhenClaimingTheNextImageFails() {
        UUID operatorId = insertOperator("claim-failure");
        String imageId = insertImage(
                62,
                Instant.parse("2026-07-30T10:00:00Z"),
                "bj_igt",
                "session",
                false,
                true
        );
        queueService.claim(operatorId, ReviewFilters.none()).orElseThrow();
        doThrow(new IllegalStateException("queue unavailable"))
                .when(queueService)
                .claim(
                        eq(operatorId),
                        any(ReviewFilters.class),
                        eq(false),
                        eq(true)
                );

        assertThatThrownBy(() -> workflowService.decideAndClaimNext(
                imageId,
                operatorId,
                Decision.ACCEPTED,
                ReviewFilters.none()
        )).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM review_task WHERE image_id = ?",
                String.class,
                imageId
        )).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT total_checked
                FROM operator_daily_statistics
                WHERE operator_id = ?
                """, Long.class, operatorId)).isEqualTo(1L);
    }

    @Test
    void calculatesPermanentUtcDailyOperatorStatisticsAndFillsMissingDays() {
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-07-30T12:00:00Z"),
                ZoneOffset.UTC
        );
        StatisticsService service = new StatisticsServiceImpl(statisticsRepository, fixedClock);
        UUID operatorId = insertOperator("statistics");
        insertDailyStatistics(operatorId, "2026-07-20", 1, 1, 0);
        insertDailyStatistics(operatorId, "2026-07-29", 3, 2, 1);
        insertDailyStatistics(operatorId, "2026-07-30", 1, 1, 0);

        OperatorStatistics statistics = service.forOperator(operatorId);

        assertThat(statistics.today()).isEqualTo(1);
        assertThat(statistics.lastSevenDays()).isEqualTo(4);
        assertThat(statistics.allTime()).isEqualTo(5);
        assertThat(statistics.matched()).isEqualTo(3);
        assertThat(statistics.notMatched()).isEqualTo(1);
        assertThat(statistics.daily()).hasSize(7);
        assertThat(statistics.daily().getFirst().date().toString()).isEqualTo("2026-07-24");
        assertThat(statistics.daily().getFirst().total()).isZero();
        assertThat(statistics.daily().getLast().date().toString()).isEqualTo("2026-07-30");
        assertThat(statistics.daily().getLast().total()).isOne();
    }

    private List<Optional<ReviewItem>> claimConcurrently(UUID first, UUID second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<ReviewItem>> firstResult = executor.submit(() -> {
                start.await();
                return queueService.claim(first, ReviewFilters.none());
            });
            Future<Optional<ReviewItem>> secondResult = executor.submit(() -> {
                start.await();
                return queueService.claim(second, ReviewFilters.none());
            });
            start.countDown();
            return List.of(firstResult.get(), secondResult.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
