package com.introlabsystems.recognitionvalidator.watcher;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ImageProcessingBackoffTest {

    @Test
    void backsOffAtTwoFiveFifteenAndThenThirtySeconds() {
        ImageProcessingBackoff backoff = new ImageProcessingBackoff();
        Instant now = Instant.parse("2026-08-01T10:00:00Z");

        assertThat(backoff.canAttempt(now)).isTrue();
        assertThat(backoff.recordFailure(now)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.canAttempt(now.plusSeconds(1))).isFalse();
        assertThat(backoff.canAttempt(now.plusSeconds(2))).isTrue();
        assertThat(backoff.recordFailure(now.plusSeconds(2))).isEqualTo(Duration.ofSeconds(5));
        assertThat(backoff.recordFailure(now.plusSeconds(7))).isEqualTo(Duration.ofSeconds(15));
        assertThat(backoff.recordFailure(now.plusSeconds(22))).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.recordFailure(now.plusSeconds(52))).isEqualTo(Duration.ofSeconds(30));

        backoff.reset();

        assertThat(backoff.canAttempt(now)).isTrue();
        assertThat(backoff.recordFailure(now)).isEqualTo(Duration.ofSeconds(2));
    }
}
