package com.introlabsystems.recognitionvalidator.slack;

import jakarta.validation.constraints.AssertTrue;
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
        @DefaultValue("09:00") @NotNull LocalTime summaryTimeUtc,
        @DefaultValue("5m") @NotNull Duration pollInterval
) {
    @AssertTrue(message = "Slack monitoring durations must be positive; polling must be at least 30 seconds")
    public boolean isTimingValid() {
        return pollInterval != null && pollInterval.compareTo(Duration.ofSeconds(30)) >= 0;
    }
}
