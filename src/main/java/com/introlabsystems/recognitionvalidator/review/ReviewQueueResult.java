package com.introlabsystems.recognitionvalidator.review;

import java.util.Optional;

public record ReviewQueueResult(Optional<ReviewItem> item, Long remaining) {

    public ReviewQueueResult {
        item = item == null ? Optional.empty() : item;
    }
}
