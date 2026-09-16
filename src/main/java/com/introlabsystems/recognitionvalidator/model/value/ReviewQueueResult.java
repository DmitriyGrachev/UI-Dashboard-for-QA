package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;
import java.util.Optional;

public record ReviewQueueResult(Optional<ReviewItem> item, ReviewQueueSummary summary, String nextImageId) {

    public ReviewQueueResult(Optional<ReviewItem> item, ReviewQueueSummary summary) {
        this(item, summary, null);
    }

    public ReviewQueueResult {
        item = item == null ? Optional.empty() : item;
        if (item.isEmpty()) nextImageId = null;
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
