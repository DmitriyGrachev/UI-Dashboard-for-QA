package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;
import java.util.Optional;

public record ReviewQueueResult(Optional<ReviewItem> item, ReviewQueueSummary summary) {

    public ReviewQueueResult {
        item = item == null ? Optional.empty() : item;
    }

    public Long remaining() {
        return summary == null ? null : summary.remaining();
    }

    public Instant oldestCreatedAt() {
        return summary == null ? null : summary.oldestCreatedAt();
    }

    public Instant newestCreatedAt() {
        return summary == null ? null : summary.newestCreatedAt();
    }
}
