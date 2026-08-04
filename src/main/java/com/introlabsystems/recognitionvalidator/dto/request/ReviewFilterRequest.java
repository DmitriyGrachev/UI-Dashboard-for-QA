package com.introlabsystems.recognitionvalidator.dto.request;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import jakarta.validation.constraints.AssertTrue;

import java.time.Instant;

public record ReviewFilterRequest(
        Instant createdFrom,
        Instant createdTo,
        String sessionId,
        String gameCode,
        Boolean notification,
        Boolean hasUserHand
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
                notification,
                hasUserHand
        );
    }
}
