package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackRejectedNotificationServiceImplTest {

    @Test
    void archiveDeliveryUsesItsStoredTargetAndFixedPayload() {
        RejectedBacklogRepository backlog = mock(RejectedBacklogRepository.class);
        SlackMessageFormatter formatter = mock(SlackMessageFormatter.class);
        SlackWebApiClient slack = mock(SlackWebApiClient.class);
        SlackNotificationStateRepository stateRepository = mock(SlackNotificationStateRepository.class);
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackProperties properties = new SlackProperties(
                true, "token", "channel", 10, "https://validator.example/archive",
                Duration.ofSeconds(2), Duration.ofSeconds(3), "https://slack.test"
        );
        SlackRejectedNotificationServiceImpl service = new SlackRejectedNotificationServiceImpl(
                properties, backlog, formatter, slack, stateRepository,
                Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC), outbox
        );
        UUID cycleId = UUID.randomUUID();
        SlackNotificationOutboxRepository.OutboxItem operation =
                new SlackNotificationOutboxRepository.OutboxItem(
                        1L, cycleId, UUID.randomUUID(), SlackNotificationOutbox.OperationKind.ARCHIVE,
                        "closed archive", "ignored snapshot target",
                        SlackNotificationOutbox.DeliveryPhase.IN_FLIGHT,
                        0, Instant.now(), Instant.now()
                );
        when(outbox.targetMessageTs(1L)).thenReturn("123.456");

        service.deliver(operation);

        verify(slack).updateMessage("123.456", "closed archive");
    }

    @Test
    void archiveWithoutTargetPostsClosedRecordWithoutChangingActiveState() {
        RejectedBacklogRepository backlog = mock(RejectedBacklogRepository.class);
        SlackMessageFormatter formatter = mock(SlackMessageFormatter.class);
        SlackWebApiClient slack = mock(SlackWebApiClient.class);
        SlackNotificationStateRepository stateRepository = mock(SlackNotificationStateRepository.class);
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackProperties properties = new SlackProperties(
                true, "token", "channel", 10, " ", Duration.ofSeconds(2),
                Duration.ofSeconds(3), "https://slack.test"
        );
        SlackRejectedNotificationServiceImpl service = new SlackRejectedNotificationServiceImpl(
                properties, backlog, formatter, slack, stateRepository, Clock.systemUTC(), outbox
        );
        SlackNotificationOutboxRepository.OutboxItem operation =
                new SlackNotificationOutboxRepository.OutboxItem(
                        2L, UUID.randomUUID(), UUID.randomUUID(), SlackNotificationOutbox.OperationKind.ARCHIVE,
                        "closed archive", null, SlackNotificationOutbox.DeliveryPhase.IN_FLIGHT,
                        0, Instant.now(), Instant.now()
                );

        service.deliver(operation);

        verify(slack).postMessage("closed archive");
    }

    @Test
    void refreshPostAttachesReturnedTimestampToItsCycle() {
        RejectedBacklogRepository backlog = mock(RejectedBacklogRepository.class);
        SlackMessageFormatter formatter = mock(SlackMessageFormatter.class);
        SlackWebApiClient slack = mock(SlackWebApiClient.class);
        SlackNotificationStateRepository stateRepository = mock(SlackNotificationStateRepository.class);
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackProperties properties = properties();
        SlackRejectedNotificationServiceImpl service = new SlackRejectedNotificationServiceImpl(
                properties, backlog, formatter, slack, stateRepository, Clock.systemUTC(), outbox
        );
        UUID cycleId = UUID.randomUUID();
        SlackNotificationState state = SlackNotificationState.cycle(cycleId);
        when(stateRepository.findById(SlackNotificationState.SINGLETON_ID))
                .thenReturn(Optional.of(state));
        when(backlog.snapshot(10)).thenReturn(new RejectedBacklogSnapshot(1, List.of()));
        when(formatter.backlog(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.anyString())).thenReturn("backlog");
        when(slack.postMessage("backlog")).thenReturn("new.ts");

        service.deliver(new SlackNotificationOutboxRepository.OutboxItem(
                3L, cycleId, null, SlackNotificationOutbox.OperationKind.REFRESH,
                null, null, SlackNotificationOutbox.DeliveryPhase.IN_FLIGHT,
                0, Instant.now(), Instant.now()
        ));

        verify(outbox).attachMessageToCycle(cycleId, "new.ts");
    }

    @Test
    void missingActiveMessagePostsReplacementAndAttachesItToArchiveTarget() {
        RejectedBacklogRepository backlog = mock(RejectedBacklogRepository.class);
        SlackMessageFormatter formatter = mock(SlackMessageFormatter.class);
        SlackWebApiClient slack = mock(SlackWebApiClient.class);
        SlackNotificationStateRepository stateRepository = mock(SlackNotificationStateRepository.class);
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackRejectedNotificationServiceImpl service = new SlackRejectedNotificationServiceImpl(
                properties(), backlog, formatter, slack, stateRepository, Clock.systemUTC(), outbox
        );
        UUID cycleId = UUID.randomUUID();
        SlackNotificationState state = SlackNotificationState.cycle(cycleId);
        state.setActiveMessageTs("deleted.ts");
        when(stateRepository.findById(SlackNotificationState.SINGLETON_ID))
                .thenReturn(Optional.of(state));
        when(backlog.snapshot(10)).thenReturn(new RejectedBacklogSnapshot(1, List.of()));
        when(formatter.backlog(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.anyString())).thenReturn("backlog");
        org.mockito.Mockito.doThrow(new SlackApiException("missing", "message_not_found"))
                .when(slack).updateMessage("deleted.ts", "backlog");
        when(slack.postMessage("backlog")).thenReturn("replacement.ts");

        service.deliver(new SlackNotificationOutboxRepository.OutboxItem(
                4L, cycleId, null, SlackNotificationOutbox.OperationKind.REFRESH,
                null, "deleted.ts", SlackNotificationOutbox.DeliveryPhase.IN_FLIGHT,
                0, Instant.now(), Instant.now()
        ));

        verify(outbox).attachMessageToCycle(cycleId, "replacement.ts");
    }

    private static SlackProperties properties() {
        return new SlackProperties(
                true, "token", "channel", 10, "https://validator.example/archive",
                Duration.ofSeconds(2), Duration.ofSeconds(3), "https://slack.test"
        );
    }
}
