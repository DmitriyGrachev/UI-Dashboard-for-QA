package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.slack.SlackNotificationOutboxRepository;
import com.introlabsystems.recognitionvalidator.slack.SlackNotificationScheduler;
import com.introlabsystems.recognitionvalidator.slack.SlackRejectedNotificationServiceImpl;
import com.introlabsystems.recognitionvalidator.slack.SlackWebApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "slack.enabled=true",
        "slack.bot-token=test-token",
        "slack.channel-id=test-channel",
        "slack.rejected-details-limit=10",
        "slack.rejected-archive-url=https://validator.test/rejected"
})
class SlackCycleRaceTest extends AbstractWebIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Instant FILTER_FROM = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant FILTER_TO = Instant.parse("2026-08-21T00:00:00Z");
    private static final String ADMIN = "admin";

    @MockitoBean
    private SlackNotificationScheduler scheduler;

    @MockitoBean
    private SlackWebApiClient slack;

    @MockitoBean
    private Clock clock;

    @Autowired
    private SlackNotificationOutboxRepository outbox;

    @Autowired
    private SlackRejectedNotificationServiceImpl notifications;

    @BeforeEach
    void resetSlackCycleState() {
        jdbc.execute("TRUNCATE TABLE slack_notification_outbox, slack_notification_state "
                + "RESTART IDENTITY CASCADE");
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void refreshPostCanCommitExportBeforeItsMessageIsAttached() throws Exception {
        String firstId = insertRejectedExportImage(501, "first.png", new byte[]{1});
        String secondId = insertRejectedExportImage(502, "second.png", new byte[]{2});
        setProcessedAt(firstId, Instant.parse("2026-08-20T10:00:00Z"));
        setProcessedAt(secondId, Instant.parse("2026-08-21T10:00:00Z"));

        outbox.initializeState();
        notifications.refreshRejectedBacklog();

        AtomicInteger posts = new AtomicInteger();
        ByteArrayOutputStream partialExport = new ByteArrayOutputStream();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (posts.getAndIncrement() == 0) {
                assertThat(rejectedExports.writeZip(
                        FILTER_FROM, FILTER_TO, false, partialExport, ADMIN)).isEqualTo(1);
                return "A";
            }
            return "B";
        }).when(slack).postMessage(anyString());

        SlackNotificationScheduler actualScheduler = actualScheduler();
        actualScheduler.drainOnce();
        actualScheduler.drainOnce();
        actualScheduler.drainOnce();

        assertThat(readZipEntries(partialExport.toByteArray()).keySet())
                .containsExactly("first.png");
        String archive = archivePayload();
        assertThat(archive).contains(
                "*1 file* downloaded by `admin`",
                "Remaining: *1*",
                "20 Aug 2026, 12:00 UTC");
        verify(slack).updateMessage(eq("A"), eq(archive));
        assertThat(posts.get()).isEqualTo(2);
        assertThat(activeMessage()).isEqualTo("B");
        assertThat(archiveTarget()).isEqualTo("A");
        assertThat(archivePhase()).isEqualTo("DELIVERED");
        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    void replacementPostGetsQueuedArchiveTargetBeforeTheReplacementIsReturned() throws Exception {
        String firstId = insertRejectedExportImage(503, "first.png", new byte[]{1});
        String secondId = insertRejectedExportImage(504, "second.png", new byte[]{2});
        setProcessedAt(firstId, Instant.parse("2026-08-20T10:00:00Z"));
        setProcessedAt(secondId, Instant.parse("2026-08-21T10:00:00Z"));

        UUID cycleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO slack_notification_state
                    (id, active_cycle_id, active_message_ts, legacy_pointer_retired)
                VALUES (1, ?, 'gone', TRUE)
                """, cycleId);
        outbox.enqueueRefresh(NOW);

        doThrow(new com.introlabsystems.recognitionvalidator.slack.SlackApiException(
                "missing", "message_not_found"))
                .when(slack).updateMessage(eq("gone"), anyString());

        AtomicInteger posts = new AtomicInteger();
        List<String> posted = new ArrayList<>();
        ByteArrayOutputStream partialExport = new ByteArrayOutputStream();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String text = invocation.getArgument(0, String.class);
            posted.add(text);
            if (text.contains("Rejected archive downloaded")) {
                throw new AssertionError("closed archive must update the replacement message");
            }
            if (posts.getAndIncrement() == 0) {
                assertThat(rejectedExports.writeZip(
                        FILTER_FROM, FILTER_TO, false, partialExport, ADMIN)).isEqualTo(1);
                return "replacement";
            }
            return "B";
        }).when(slack).postMessage(anyString());

        SlackNotificationScheduler actualScheduler = actualScheduler();
        actualScheduler.drainOnce();
        actualScheduler.drainOnce();
        actualScheduler.drainOnce();

        assertThat(readZipEntries(partialExport.toByteArray()).keySet())
                .containsExactly("first.png");
        String archive = archivePayload();
        assertThat(archive).contains(
                "*1 file* downloaded by `admin`",
                "Remaining: *1*",
                "20 Aug 2026, 12:00 UTC");
        verify(slack).updateMessage(eq("replacement"), eq(archive));
        assertThat(posted).noneMatch(text -> text.contains("Rejected archive downloaded"));
        assertThat(activeMessage()).isEqualTo("B");
        assertThat(archiveTarget()).isEqualTo("replacement");
        assertThat(archivePhase()).isEqualTo("DELIVERED");
        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    void refreshDuringRetryPausePreservesTheRetryDeadline() {
        outbox.initializeState();
        notifications.refreshRejectedBacklog();

        String worker = "worker-1";
        var operation = outbox.claimNext(worker, NOW, Duration.ofMinutes(2)).orElseThrow();
        Instant retryAt = NOW.plusSeconds(37);
        outbox.markRetry(operation.id(), worker, retryAt, "paused");

        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        notifications.refreshRejectedBacklog();

        assertThat(nextAttemptAt()).isEqualTo(retryAt);
        assertThat(outbox.claimNext("worker-2", retryAt.minusSeconds(1), Duration.ofMinutes(2)))
                .isEmpty();
        assertThat(outbox.claimNext("worker-2", retryAt, Duration.ofMinutes(2)))
                .hasValueSatisfying(claimed -> assertThat(claimed.id()).isEqualTo(operation.id()));
    }

    private SlackNotificationScheduler actualScheduler() {
        return new SlackNotificationScheduler(outbox, notifications, clock);
    }

    private void setProcessedAt(String imageId, Instant processedAt) {
        jdbc.update("UPDATE image_asset SET processed_at = ? WHERE id = ?",
                Timestamp.from(processedAt), imageId);
    }

    private String archivePayload() {
        return jdbc.queryForObject(
                "SELECT archive_payload FROM slack_notification_outbox "
                        + "WHERE operation_kind = 'ARCHIVE'", String.class);
    }

    private String archiveTarget() {
        return jdbc.queryForObject(
                "SELECT target_message_ts FROM slack_notification_outbox "
                        + "WHERE operation_kind = 'ARCHIVE'", String.class);
    }

    private String archivePhase() {
        return jdbc.queryForObject(
                "SELECT delivery_phase FROM slack_notification_outbox "
                        + "WHERE operation_kind = 'ARCHIVE'", String.class);
    }

    private Instant nextAttemptAt() {
        return jdbc.queryForObject(
                "SELECT next_attempt_at FROM slack_notification_outbox", Timestamp.class).toInstant();
    }

    private String activeMessage() {
        return jdbc.queryForObject(
                "SELECT active_message_ts FROM slack_notification_state WHERE id = 1",
                String.class);
    }
}
