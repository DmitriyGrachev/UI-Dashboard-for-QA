package com.introlabsystems.recognitionvalidator.web.dto;

import com.introlabsystems.recognitionvalidator.review.ReviewQueueResult;

public record ReviewQueueResponse(ReviewItemResponse item, Long remaining) {

    public static ReviewQueueResponse from(ReviewQueueResult result) {
        ReviewItemResponse item = result.item()
                .map(ReviewItemResponse::from)
                .orElse(null);
        Long remaining = result.remaining();
        if (item == null && remaining == null) {
            remaining = 0L;
        }
        return new ReviewQueueResponse(item, remaining);
    }
}
