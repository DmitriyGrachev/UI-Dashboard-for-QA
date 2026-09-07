package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "slack.enabled=false"
})
class SlackOperationsDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    @Autowired
    private SlackNotificationOutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @SpyBean
    private NamedParameterJdbcTemplate namedJdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE slack_notification_outbox, slack_notification_state "
                + "RESTART IDENTITY CASCADE");
    }

    @Test
    void dailyMessageDeduplicatesByKey() {
        UUID series = UUID.randomUUID();

        assertThat(outbox.enqueueMessage("daily:2026-09-07", series, "daily summary", NOW))
                .isTrue();
        assertThat(outbox.enqueueMessage("daily:2026-09-07", series, "daily summary", NOW.plusSeconds(1)))
                .isFalse();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slack_notification_outbox "
                + "WHERE operation_kind = 'MESSAGE'", Long.class)).isOne();
    }

    @Test
    void laterMessageInheritsTargetFromDeliveredMessage() {
        UUID series = UUID.randomUUID();
        outbox.enqueueMessage("incident:open", series, "incident text", NOW);

        var first = outbox.claimNext("worker", NOW, Duration.ofMinutes(2)).orElseThrow();
        outbox.attachMessageToCycle(series, "123.456");
        outbox.markDelivered(first.id(), "worker");

        assertThat(outbox.enqueueMessage("incident:update", series, "incident text", NOW.plusSeconds(1)))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT target_message_ts FROM slack_notification_outbox "
                + "WHERE dedup_key = 'incident:update'", String.class)).isEqualTo("123.456");
    }

    @Test
    void messageDeliveryPostsMissingMessageAndAttachesReplacement() {
        SlackWebApiClient slack = mock(SlackWebApiClient.class);
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackRejectedNotificationServiceImpl service = service(slack, outbox);
        UUID series = UUID.randomUUID();
        SlackNotificationOutboxRepository.OutboxItem operation = operation(
                series, SlackNotificationOutbox.OperationKind.MESSAGE, "fixed text", null);
        when(slack.postMessage("fixed text")).thenReturn("replacement.ts");

        service.deliver(operation);

        verify(slack).postMessage("fixed text");
        verify(outbox).attachMessageToCycle(series, "replacement.ts");
    }

    @Test
    void messageDeliveryReplacesDeletedTargetWithSamePayload() {
        SlackWebApiClient slack = mock(SlackWebApiClient.class);
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackRejectedNotificationServiceImpl service = service(slack, outbox);
        UUID series = UUID.randomUUID();
        SlackNotificationOutboxRepository.OutboxItem operation = operation(
                series, SlackNotificationOutbox.OperationKind.MESSAGE, "fixed text", "deleted.ts");
        when(outbox.targetMessageTs(operation.id())).thenReturn("deleted.ts");
        doThrow(new SlackApiException("missing", "message_not_found"))
                .when(slack).updateMessage("deleted.ts", "fixed text");
        when(slack.postMessage("fixed text")).thenReturn("replacement.ts");

        service.deliver(operation);

        verify(slack).postMessage("fixed text");
        verify(outbox).attachMessageToCycle(series, "replacement.ts");
    }

    @Test
    void differentSeriesDoNotInheritTargets() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        outbox.enqueueMessage("first", first, "first", NOW);
        var claimed = outbox.claimNext("worker", NOW, Duration.ofMinutes(2)).orElseThrow();
        outbox.attachMessageToCycle(first, "first.ts");
        outbox.markDelivered(claimed.id(), "worker");

        outbox.enqueueMessage("second", second, "second", NOW.plusSeconds(1));

        assertThat(jdbc.queryForObject("SELECT target_message_ts FROM slack_notification_outbox "
                + "WHERE dedup_key = 'second'", String.class)).isNull();
    }

    @Test
    void attachWaitsForConcurrentEnqueueAfterItsTargetRead() throws Exception {
        CountDownLatch targetRead = new CountDownLatch(1);
        CountDownLatch releaseTargetRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            Object result = invocation.callRealMethod();
            if (sql.contains("operation_kind = 'MESSAGE'") && sql.contains("ORDER BY id DESC")) {
                targetRead.countDown();
                if (!releaseTargetRead.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release enqueue");
                }
            }
            return result;
        }).when(namedJdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        UUID series = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var enqueue = executor.submit(() ->
                    outbox.enqueueMessage("incident:racing", series, "racing", NOW));
            assertThat(targetRead.await(5, TimeUnit.SECONDS)).isTrue();

            var attach = executor.submit(() -> outbox.attachMessageToCycle(series, "new.ts"));
            assertThatThrownBy(() -> attach.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseTargetRead.countDown();
            assertThat(enqueue.get(5, TimeUnit.SECONDS)).isTrue();
            attach.get(5, TimeUnit.SECONDS);
        } finally {
            releaseTargetRead.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT target_message_ts FROM slack_notification_outbox "
                + "WHERE dedup_key = 'incident:racing'", String.class)).isEqualTo("new.ts");
    }

    private SlackRejectedNotificationServiceImpl service(
            SlackWebApiClient slack,
            SlackNotificationOutboxRepository outbox
    ) {
        SlackProperties properties = new SlackProperties(
                true, "token", "channel", 10, "https://validator.example/archive",
                Duration.ofSeconds(2), Duration.ofSeconds(3), "https://slack.test"
        );
        return new SlackRejectedNotificationServiceImpl(
                properties,
                mock(RejectedBacklogRepository.class),
                mock(SlackMessageFormatter.class),
                slack,
                mock(SlackNotificationStateRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                outbox
        );
    }

    private static SlackNotificationOutboxRepository.OutboxItem operation(
            UUID series,
            SlackNotificationOutbox.OperationKind kind,
            String text,
            String target
    ) {
        return new SlackNotificationOutboxRepository.OutboxItem(
                99L, series, null, kind, text, target,
                SlackNotificationOutbox.DeliveryPhase.IN_FLIGHT,
                0, NOW, NOW
        );
    }

    private void deliverNext(
            SlackRejectedNotificationServiceImpl service,
            String worker,
            Instant now
    ) {
        var operation = outbox.claimNext(worker, now, Duration.ofMinutes(2)).orElseThrow();
        service.deliver(operation);
        outbox.markDelivered(operation.id(), worker);
    }

    private static final class RecordingSlackClient extends SlackWebApiClient {
        private final List<String> posts = new ArrayList<>();
        private final List<SlackUpdate> updates = new ArrayList<>();

        private RecordingSlackClient() {
            super(null, null);
        }

        @Override
        public String postMessage(String text) {
            posts.add(text);
            return "123.456";
        }

        @Override
        public void updateMessage(String messageTs, String text) {
            updates.add(new SlackUpdate(messageTs, text));
        }
    }

    private record SlackUpdate(String messageTs, String text) {
    }
}
