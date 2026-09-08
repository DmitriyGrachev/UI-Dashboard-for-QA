package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SlackNotificationSchedulerLoggingTest {

    @Test
    void unexpectedDeliveryFailureLogsOutboxContextAndStack(CapturedOutput output) {
        Instant now = Instant.parse("2026-09-08T08:00:00Z");
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackRejectedNotificationServiceImpl notifications = mock(SlackRejectedNotificationServiceImpl.class);
        var operation = new SlackNotificationOutboxRepository.OutboxItem(
                42L,
                UUID.randomUUID(),
                null,
                SlackNotificationOutbox.OperationKind.MESSAGE,
                "private Slack payload",
                null,
                SlackNotificationOutbox.DeliveryPhase.IN_FLIGHT,
                2,
                now,
                now
        );
        when(notifications.canDeliver()).thenReturn(true);
        when(outbox.claimNext(anyString(), eq(now), eq(Duration.ofMinutes(2))))
                .thenReturn(Optional.of(operation));
        doThrow(new IllegalStateException("unexpected delivery failure"))
                .when(notifications).deliver(operation);
        SlackNotificationScheduler scheduler = new SlackNotificationScheduler(
                outbox,
                notifications,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        scheduler.drainOnce();

        assertThat(output)
                .contains("ERROR")
                .contains("Unexpected Slack notification delivery failure: id=42, kind=MESSAGE, attempts=2")
                .contains("unexpected delivery failure")
                .doesNotContain("private Slack payload");
    }
}
