package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;

public record ReviewFilters(
        Instant createdFrom,
        Instant createdTo,
        Long tokenId,
        String sessionId,
        String gameCode,
        Boolean notification,
        Boolean hasUserHand,
        com.introlabsystems.recognitionvalidator.ai.model.AiResultState aiResult,
        Integer certaintyFrom,
        Integer certaintyTo
) {

    public ReviewFilters(Instant createdFrom, Instant createdTo, Long tokenId, String sessionId,
                         String gameCode, Boolean notification, Boolean hasUserHand) {
        this(createdFrom, createdTo, tokenId, sessionId, gameCode, notification, hasUserHand, null, null, null);
    }

    public static ReviewFilters none() {
        return new ReviewFilters(null, null, null, null, null, null, null);
    }
}
