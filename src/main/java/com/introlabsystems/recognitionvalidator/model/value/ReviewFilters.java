package com.introlabsystems.recognitionvalidator.model.value;

import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;

import java.time.Instant;

public record ReviewFilters(
        Instant createdFrom,
        Instant createdTo,
        Long tokenId,
        String sessionId,
        String gameCode,
        Boolean notification,
        Boolean hasUserHand,
        AiResultState aiResult
) {

    public ReviewFilters(Instant createdFrom, Instant createdTo, Long tokenId, String sessionId,
                         String gameCode, Boolean notification, Boolean hasUserHand) {
        this(createdFrom, createdTo, tokenId, sessionId, gameCode, notification, hasUserHand, null);
    }

    public static ReviewFilters none() {
        return new ReviewFilters(null, null, null, null, null, null, null);
    }
}
