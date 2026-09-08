package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SlackNotificationScheduler {

    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
    private static final Duration[] RETRY_DELAYS = {
            Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofMinutes(1),
            Duration.ofMinutes(5)
    };

    private final SlackNotificationOutboxRepository outbox;
    private final SlackRejectedNotificationServiceImpl notifications;
    private final Clock clock;

    @Scheduled(fixedDelay = 1000, scheduler = "slackDeliveryScheduler")
    public void drainOnce() {
        if (!notifications.canDeliver()) {
            return;
        }
        String workerId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        var operation = outbox.claimNext(workerId, now, CLAIM_LEASE).orElse(null);
        if (operation == null) {
            return;
        }
        try {
            notifications.deliver(operation);
            outbox.markDelivered(operation.id(), workerId);
            log.debug(
                    "Slack notification delivered: id={}, kind={}, attempts={}",
                    operation.id(),
                    operation.operationKind(),
                    operation.attempts()
            );
        } catch (SlackApiException exception) {
            Instant failedAt = clock.instant();
            if (exception.isRetryable()) {
                outbox.markRetry(
                        operation.id(), workerId,
                        nextAttempt(operation.attempts(), failedAt, exception.retryAfter()),
                        safeMessage(exception)
                );
            } else {
                outbox.markBlocked(operation.id(), workerId, safeMessage(exception));
            }
            if (exception.isRetryable()) {
                log.warn(
                        "Slack notification delivery will retry: id={}, kind={}, attempts={}, status={}, code={}, error={}",
                        operation.id(),
                        operation.operationKind(),
                        operation.attempts(),
                        exception.statusCode(),
                        exception.errorCode(),
                        safeMessage(exception)
                );
            } else {
                log.error(
                        "Slack notification delivery blocked: id={}, kind={}, attempts={}, status={}, code={}",
                        operation.id(),
                        operation.operationKind(),
                        operation.attempts(),
                        exception.statusCode(),
                        exception.errorCode(),
                        exception
                );
            }
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();
            outbox.markRetry(
                    operation.id(), workerId,
                    nextAttempt(operation.attempts(), failedAt, null),
                    safeMessage(exception)
            );
            log.error(
                    "Unexpected Slack notification delivery failure: id={}, kind={}, attempts={}",
                    operation.id(),
                    operation.operationKind(),
                    operation.attempts(),
                    exception
            );
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        outbox.initializeState();
        boolean enabled = notifications.canDeliver();
        log.info("Slack notification delivery initialized: enabled={}", enabled);
        if (enabled) {
            notifications.refreshRejectedBacklog();
        }
    }

    private Instant nextAttempt(int attempts, Instant now, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative()) {
            return now.plus(retryAfter);
        }
        int index = Math.min(Math.max(attempts, 0), RETRY_DELAYS.length - 1);
        return now.plus(RETRY_DELAYS[index]);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }
}
