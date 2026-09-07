package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackNotificationDeliveryTest {

    @Test
    void retryAfterIsPersistedFromTimeTheRequestFails() {
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackRejectedNotificationServiceImpl notifications =
                mock(SlackRejectedNotificationServiceImpl.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC);
        SlackNotificationScheduler scheduler =
                new SlackNotificationScheduler(outbox, notifications, clock);
        SlackNotificationOutboxRepository.OutboxItem operation = operation(1L);
        when(notifications.canDeliver()).thenReturn(true);
        when(outbox.claimNext(
                org.mockito.ArgumentMatchers.anyString(), eq(clock.instant()),
                eq(Duration.ofMinutes(2))
        )).thenReturn(Optional.of(operation));
        doThrow(new SlackApiException(
                "rate limited", "ratelimited", 429, Duration.ofSeconds(37), true
        )).when(notifications).deliver(operation);

        scheduler.drainOnce();

        verify(outbox).markRetry(
                eq(1L), org.mockito.ArgumentMatchers.anyString(),
                eq(Instant.parse("2026-09-07T10:00:37Z")), eq("rate limited")
        );
        verify(outbox, never()).markDelivered(eq(1L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void permanentErrorRemainsBlockedWithAQuietRetryDeadline() {
        SlackNotificationOutboxRepository outbox = mock(SlackNotificationOutboxRepository.class);
        SlackRejectedNotificationServiceImpl notifications =
                mock(SlackRejectedNotificationServiceImpl.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC);
        SlackNotificationScheduler scheduler =
                new SlackNotificationScheduler(outbox, notifications, clock);
        SlackNotificationOutboxRepository.OutboxItem operation = operation(2L);
        when(notifications.canDeliver()).thenReturn(true);
        when(outbox.claimNext(
                org.mockito.ArgumentMatchers.anyString(), eq(clock.instant()),
                eq(Duration.ofMinutes(2))
        )).thenReturn(Optional.of(operation));
        doThrow(new SlackApiException(
                "invalid auth", "invalid_auth", 401, null, false
        )).when(notifications).deliver(operation);

        scheduler.drainOnce();

        verify(outbox).markBlocked(eq(2L), org.mockito.ArgumentMatchers.anyString(),
                eq("invalid auth"));
        verify(outbox, never()).markDelivered(eq(2L), org.mockito.ArgumentMatchers.anyString());
    }

    private static SlackNotificationOutboxRepository.OutboxItem operation(long id) {
        return new SlackNotificationOutboxRepository.OutboxItem(
                id, UUID.randomUUID(), null, SlackNotificationOutbox.OperationKind.REFRESH,
                null, null, SlackNotificationOutbox.DeliveryPhase.IN_FLIGHT, 0,
                Instant.parse("2026-09-07T10:00:00Z"), Instant.parse("2026-09-07T09:59:00Z")
        );
    }
}
