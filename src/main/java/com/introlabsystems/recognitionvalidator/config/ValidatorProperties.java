package com.introlabsystems.recognitionvalidator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("validator")
public record ValidatorProperties(
        @NotNull Path imageRoot,
        @NotEmpty List<String> games,
        @Min(1) int batchSize,
        @NotNull Duration leaseDuration,
        @NotNull Duration retention,
        @Min(1) int cleanupBatchSize,
        @Min(1) int cleanupMaxBatches,
        boolean watchEnabled,
        @NotNull Duration watchFlushInterval,
        @Min(1) int watchMaxPendingEvents,
        boolean countRemainingScreenshots
) {
}
