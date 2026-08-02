package com.introlabsystems.recognitionvalidator.image;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class ImageProcessingBackoff {

    private static final List<Duration> DELAYS = List.of(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30)
    );

    private int failures;
    private Instant retryAt = Instant.MIN;

    synchronized boolean canAttempt(Instant now) {
        return !now.isBefore(retryAt);
    }

    synchronized Duration recordFailure(Instant now) {
        Duration delay = DELAYS.get(Math.min(failures, DELAYS.size() - 1));
        failures++;
        retryAt = now.plus(delay);
        return delay;
    }

    synchronized void reset() {
        failures = 0;
        retryAt = Instant.MIN;
    }
}
