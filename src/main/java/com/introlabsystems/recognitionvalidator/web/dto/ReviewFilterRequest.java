package com.introlabsystems.recognitionvalidator.web.dto;

import com.introlabsystems.recognitionvalidator.review.ReviewFilters;
import jakarta.validation.constraints.AssertTrue;

import java.time.Instant;

public record ReviewFilterRequest(
        Instant createdFrom,
        Instant createdTo,
        String sessionId,
        String gameCode,
        Boolean notification
) {

    @AssertTrue(message = "createdFrom must be earlier than createdTo")
    public boolean isDateRangeValid() {
        return createdFrom == null || createdTo == null || createdFrom.isBefore(createdTo);
    }

    public ReviewFilters toFilters() {
        return new ReviewFilters(
                createdFrom,
                createdTo,
                sessionId,
                gameCode,
                notification
        );
    }
}
