package com.introlabsystems.recognitionvalidator.review;

import java.time.Instant;

public record ReviewFilters(
        Instant createdFrom,
        Instant createdTo,
        String sessionId,
        String gameCode,
        Boolean notification,
        Boolean hasUserHand
) {

    public static ReviewFilters none() {
        return new ReviewFilters(null, null, null, null, null, null);
    }
}
