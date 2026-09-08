package com.introlabsystems.recognitionvalidator.dto.request;

import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;
import com.introlabsystems.recognitionvalidator.ai.model.AiVerdict;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import jakarta.validation.constraints.AssertTrue;

import java.time.Instant;

public record ReviewFilterRequest(
        Instant createdFrom,
        Instant createdTo,
        Long tokenId,
        String sessionId,
        String gameCode,
        Boolean notification,
        Boolean hasUserHand,
        AiResultState aiResult,
        AiVerdict aiVerdict,
        Integer confidenceFrom,
        Integer confidenceTo
) {

    @AssertTrue(message = "createdFrom must be earlier than createdTo")
    public boolean isDateRangeValid() {
        return createdFrom == null || createdTo == null || createdFrom.isBefore(createdTo);
    }

    @AssertTrue(message = "confidence must be an integer range from 0 to 100")
    public boolean isConfidenceRangeValid() {
        return validPercentage(confidenceFrom) && validPercentage(confidenceTo)
                && (confidenceFrom == null || confidenceTo == null || confidenceFrom <= confidenceTo);
    }

    public ReviewFilters toFilters() {
        return new ReviewFilters(
                createdFrom,
                createdTo,
                tokenId,
                sessionId,
                gameCode,
                notification,
                hasUserHand,
                aiResult,
                aiVerdict,
                confidenceFrom,
                confidenceTo
        );
    }

    private static boolean validPercentage(Integer value) {
        return value == null || (value >= 0 && value <= 100);
    }
}
