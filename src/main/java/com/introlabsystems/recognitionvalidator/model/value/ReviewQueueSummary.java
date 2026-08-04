package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;

public record ReviewQueueSummary(
        long remaining,
        Instant oldestCreatedAt,
        Instant newestCreatedAt
) {

    public ReviewQueueSummary including(Instant createdAt) {
        if (createdAt == null) {
            return new ReviewQueueSummary(remaining + 1, oldestCreatedAt, newestCreatedAt);
        }
        Instant oldest = oldestCreatedAt == null || createdAt.isBefore(oldestCreatedAt)
                ? createdAt
                : oldestCreatedAt;
        Instant newest = newestCreatedAt == null || createdAt.isAfter(newestCreatedAt)
                ? createdAt
                : newestCreatedAt;
        return new ReviewQueueSummary(remaining + 1, oldest, newest);
    }
}
