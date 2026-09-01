package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;

public record AdminScreenshotSummary(
        long totalCount,
        Instant oldestCreatedAt,
        Instant newestCreatedAt
) {
}
