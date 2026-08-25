package com.introlabsystems.recognitionvalidator.slack;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("slack")
public record SlackProperties(
        boolean enabled,
        String botToken,
        String channelId,
        @Min(1) int rejectedDetailsLimit,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        String apiBaseUrl
) {
}
