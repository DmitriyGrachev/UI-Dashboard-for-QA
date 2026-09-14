package com.introlabsystems.recognitionvalidator.dto.response;

import com.introlabsystems.recognitionvalidator.service.ReviewSummaryCache;

import java.time.Instant;

public record ReviewQueueSummaryResponse(
        Long remaining,
        Instant oldestCreatedAt,
        Instant newestCreatedAt,
        Instant asOf,
        boolean refreshing,
        boolean failed
) {

    public static ReviewQueueSummaryResponse from(ReviewSummaryCache.Snapshot snapshot) {
        var summary = snapshot.value();
        return new ReviewQueueSummaryResponse(
                summary == null ? null : summary.remaining(),
                summary == null ? null : summary.oldestCreatedAt(),
                summary == null ? null : summary.newestCreatedAt(),
                snapshot.asOf(), snapshot.refreshing(), snapshot.failed()
        );
    }
}
