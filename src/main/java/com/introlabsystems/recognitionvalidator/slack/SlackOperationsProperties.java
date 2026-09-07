package com.introlabsystems.recognitionvalidator.slack;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;

@Validated
@ConfigurationProperties("slack.operations")
public record SlackOperationsProperties(
        @DefaultValue("true") boolean dailySummaryEnabled,
        @DefaultValue("true") boolean alertsEnabled,
        @DefaultValue("09:00") @NotNull LocalTime summaryTimeUtc,
        @DefaultValue("5m") @NotNull Duration pollInterval,
        @DefaultValue("10m") @NotNull Duration incidentDelay,
        @DefaultValue("15m") @NotNull Duration stallDuration,
        @DefaultValue("15m") @NotNull Duration updateInterval,
        @DefaultValue("10") @Min(1) int failedUploadThreshold,
        @DefaultValue("10") @Min(1) int expiredLeaseThreshold
) {
    @AssertTrue(message = "Slack monitoring durations must be positive; polling must be at least 30 seconds")
    public boolean isTimingValid() {
        return pollInterval != null && pollInterval.compareTo(Duration.ofSeconds(30)) >= 0
                && positive(incidentDelay) && positive(stallDuration) && positive(updateInterval);
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
