package com.introlabsystems.recognitionvalidator.dto.response;

import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueSummary;

import java.time.Instant;

public record ReviewQueueSummaryResponse(
        long remaining,
        Instant oldestCreatedAt,
        Instant newestCreatedAt
) {

    public static ReviewQueueSummaryResponse from(ReviewQueueSummary summary) {
        return new ReviewQueueSummaryResponse(
                summary.remaining(),
                summary.oldestCreatedAt(),
                summary.newestCreatedAt()
        );
    }
}
