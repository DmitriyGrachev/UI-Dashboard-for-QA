package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "slack.enabled=false"
})
class SlackNotificationOutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    @Autowired
    private SlackNotificationOutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE slack_notification_outbox, slack_notification_state "
                + "RESTART IDENTITY CASCADE");
    }

    @Test
    void refreshRequestsCoalesceWithoutDroppingArchiveBoundaries() {
        outbox.initializeState();
        outbox.enqueueRefresh(NOW);
        assertThat(outbox.enqueueArchive(UUID.randomUUID(), NOW, "closed archive", true)).isTrue();
        for (int attempt = 0; attempt < 150; attempt++) {
            outbox.enqueueRefresh(NOW.plusSeconds(attempt));
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM slack_notification_outbox
                 WHERE operation_kind = 'REFRESH'
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM slack_notification_outbox
                 WHERE operation_kind = 'ARCHIVE'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void archiveBoundariesRemainOrderedAndEachGetsItsOwnNextCycle() {
        outbox.initializeState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(outbox.enqueueArchive(first, NOW, "archive one", true)).isTrue();
        assertThat(outbox.enqueueArchive(second, NOW.plusSeconds(1), "archive two", true)).isTrue();

        var rows = jdbc.queryForList("""
                SELECT id, export_id, operation_kind, cycle_id
                  FROM slack_notification_outbox
                 WHERE operation_kind = 'ARCHIVE'
                 ORDER BY id
                """);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("export_id")).isEqualTo(first);
        assertThat(rows.get(1).get("export_id")).isEqualTo(second);
        assertThat(rows.get(0).get("cycle_id")).isNotEqualTo(rows.get(1).get("cycle_id"));
    }

    @Test
    void earliestRetryBlocksLaterRowsUntilItsDeadline() {
        outbox.initializeState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        outbox.enqueueArchive(first, NOW, "archive one", true);
        outbox.enqueueArchive(second, NOW.plusSeconds(1), "archive two", true);

        String worker = "worker-1";
        var claimed = outbox.claimNext(worker, NOW, Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.exportId()).isEqualTo(first);
        assertThat(outbox.claimNext("worker-2", NOW, Duration.ofMinutes(2))).isEmpty();

        outbox.markRetry(claimed.id(), worker, NOW.plusSeconds(30), "paused");
        assertThat(outbox.claimNext("worker-2", NOW.plusSeconds(29), Duration.ofMinutes(2)))
                .isEmpty();
        assertThat(outbox.claimNext("worker-2", NOW.plusSeconds(30), Duration.ofMinutes(2)))
                .hasValueSatisfying(item -> assertThat(item.exportId()).isEqualTo(first));
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleWorkerCannotFinishIt() {
        outbox.initializeState();
        UUID exportId = UUID.randomUUID();
        outbox.enqueueArchive(exportId, NOW, "archive one", true);

        var firstClaim = outbox.claimNext("worker-1", NOW, Duration.ofMinutes(2)).orElseThrow();
        var reclaimed = outbox.claimNext("worker-2", NOW.plus(Duration.ofMinutes(3)), Duration.ofMinutes(2))
                .orElseThrow();
        assertThat(reclaimed.id()).isEqualTo(firstClaim.id());

        outbox.markDelivered(firstClaim.id(), "worker-1");
        assertThat(outbox.claimNext("worker-3", NOW.plus(Duration.ofMinutes(3)), Duration.ofMinutes(2)))
                .isEmpty();

        outbox.markDelivered(reclaimed.id(), "worker-2");
        assertThat(jdbc.queryForObject("""
                SELECT delivery_phase FROM slack_notification_outbox WHERE id = ?
                """, String.class, reclaimed.id())).isEqualTo("DELIVERED");
    }
}
