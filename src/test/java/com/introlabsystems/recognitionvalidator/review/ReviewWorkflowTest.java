package com.introlabsystems.recognitionvalidator.review;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.maintenance.RetentionCleanupService;
import com.introlabsystems.recognitionvalidator.statistics.OperatorStatistics;
import com.introlabsystems.recognitionvalidator.statistics.StatisticsRepository;
import com.introlabsystems.recognitionvalidator.statistics.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ReviewWorkflowTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewQueueService queueService;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private ValidatorProperties properties;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE review_task, image_asset, app_user CASCADE");
    }

    @Test
    void flywayCreatesValidatorTables() {
        assertThat(tableExists("app_user")).isTrue();
        assertThat(tableExists("image_asset")).isTrue();
        assertThat(tableExists("review_task")).isTrue();
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
                true
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
    void calculatesUtcOperatorStatisticsForSelectedPeriod() {
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-07-30T12:00:00Z"),
                ZoneOffset.UTC
        );
        StatisticsService service = new StatisticsService(statisticsRepository, fixedClock);
        UUID operatorId = insertOperator("statistics");
        UUID otherOperator = insertOperator("statistics-other");

        completeReview(
                insertImage(70, Instant.parse("2026-07-27T12:00:00Z"),
                        "bj_igt", "session", false, true),
                operatorId,
                Decision.ACCEPTED,
                Instant.parse("2026-07-29T01:00:00Z")
        );
        completeReview(
                insertImage(71, Instant.parse("2026-07-27T13:00:00Z"),
                        "bj_igt", "session", false, true),
                operatorId,
                Decision.ACCEPTED,
                Instant.parse("2026-07-29T02:00:00Z")
        );
        completeReview(
                insertImage(72, Instant.parse("2026-07-27T14:00:00Z"),
                        "bj_igt", "session", false, true),
                operatorId,
                Decision.REJECTED,
                Instant.parse("2026-07-29T03:00:00Z")
        );
        completeReview(
                insertImage(73, Instant.parse("2026-07-27T15:00:00Z"),
                        "bj_igt", "session", false, true),
                operatorId,
                Decision.ACCEPTED,
                Instant.parse("2026-07-30T00:00:00Z")
        );
        completeReview(
                insertImage(74, Instant.parse("2026-07-27T16:00:00Z"),
                        "bj_igt", "session", false, true),
                otherOperator,
                Decision.REJECTED,
                Instant.parse("2026-07-30T01:00:00Z")
        );

        OperatorStatistics statistics = service.forOperator(
                operatorId,
                Instant.parse("2026-07-29T00:00:00Z"),
                Instant.parse("2026-07-30T00:00:00Z")
        );

        assertThat(statistics.today()).isEqualTo(1);
        assertThat(statistics.retainedTotal()).isEqualTo(4);
        assertThat(statistics.periodTotal()).isEqualTo(3);
        assertThat(statistics.accepted()).isEqualTo(2);
        assertThat(statistics.rejected()).isEqualTo(1);
        assertThat(statistics.acceptedPercent()).isEqualByComparingTo(new BigDecimal("66.67"));
        assertThat(statistics.rejectedPercent()).isEqualByComparingTo(new BigDecimal("33.33"));

        OperatorStatistics emptyPeriod = service.forOperator(
                operatorId,
                Instant.parse("2026-07-25T00:00:00Z"),
                Instant.parse("2026-07-26T00:00:00Z")
        );
        assertThat(emptyPeriod.periodTotal()).isZero();
        assertThat(emptyPeriod.acceptedPercent()).isEqualByComparingTo("0.00");
        assertThat(emptyPeriod.rejectedPercent()).isEqualByComparingTo("0.00");
    }

    @Test
    void retentionDeletesOnlyDatabaseRecordsOlderThanSevenDays() throws Exception {
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        RetentionCleanupService service =
                new RetentionCleanupService(namedJdbc, properties, fixedClock);
        String oldImageId = insertImage(
                80,
                now.minus(properties.retention()).minusSeconds(1),
                "bj_igt",
                "old-session",
                false,
                true
        );
        String boundaryImageId = insertImage(
                81,
                now.minus(properties.retention()),
                "bj_igt",
                "boundary-session",
                false,
                true
        );
        Path physicalFile = temporaryDirectory.resolve(oldImageId + ".png");
        Files.writeString(physicalFile, "not touched by retention");

        int deleted = service.runOnce();

        assertThat(deleted).isEqualTo(1);
        assertThat(rowCount("image_asset", oldImageId)).isZero();
        assertThat(rowCount("review_task", oldImageId)).isZero();
        assertThat(rowCount("image_asset", boundaryImageId)).isOne();
        assertThat(rowCount("review_task", boundaryImageId)).isOne();
        assertThat(physicalFile).exists();
    }

    private Boolean tableExists(String tableName) {
        return jdbc.queryForObject(
                "select to_regclass('public." + tableName + "') is not null",
                Boolean.class
        );
    }

    private UUID insertOperator(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at)
                VALUES (?, ?, 'hash', TRUE, now())
                """, id, username);
        return id;
    }

    private String insertImage(
            int number,
            Instant createdAt,
            String gameCode,
            String sessionId,
            boolean notification,
            boolean available
    ) {
        String id = "%064x".formatted(number);
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code, session_id,
                    is_notification, has_stand, has_hit, has_double, has_split, parse_status
                ) VALUES (
                    ?, ?, ?, ?, ?, now(), now(), ?, ?, ?,
                    ?, FALSE, FALSE, FALSE, FALSE, 'SUCCESS'
                )
                """,
                id,
                id + ".png",
                id + ".png",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                available,
                gameCode,
                sessionId,
                notification
        );
        jdbc.update(
                "INSERT INTO review_task (image_id, status) VALUES (?, 'PENDING')",
                id
        );
        return id;
    }

    private void completeReview(
            String imageId,
            UUID operatorId,
            Decision decision,
            Instant reviewedAt
    ) {
        jdbc.update("""
                UPDATE review_task
                SET status = 'COMPLETED',
                    assigned_to = ?,
                    assigned_at = ?,
                    decision = ?,
                    reviewed_at = ?
                WHERE image_id = ?
                """,
                operatorId,
                Timestamp.from(reviewedAt.minusSeconds(30)),
                decision.name(),
                Timestamp.from(reviewedAt),
                imageId
        );
    }

    private long rowCount(String tableName, String imageId) {
        String idColumn = "image_asset".equals(tableName) ? "id" : "image_id";
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + tableName + " WHERE " + idColumn + " = ?",
                Long.class,
                imageId
        );
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
