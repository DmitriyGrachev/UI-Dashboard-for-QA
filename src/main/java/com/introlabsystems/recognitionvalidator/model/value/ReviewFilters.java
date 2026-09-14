package com.introlabsystems.recognitionvalidator.model.value;

import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;
import com.introlabsystems.recognitionvalidator.ai.model.AiVerdict;

import java.time.Instant;

public record ReviewFilters(
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

    public ReviewFilters(Instant createdFrom, Instant createdTo, Long tokenId, String sessionId,
                         String gameCode, Boolean notification, Boolean hasUserHand,
                         AiResultState aiResult) {
        this(createdFrom, createdTo, tokenId, sessionId, gameCode, notification, hasUserHand,
                aiResult, null, null, null);
    }

    public ReviewFilters(Instant createdFrom, Instant createdTo, Long tokenId, String sessionId,
                         String gameCode, Boolean notification, Boolean hasUserHand) {
        this(createdFrom, createdTo, tokenId, sessionId, gameCode, notification, hasUserHand,
                null, null, null, null);
    }

    public static ReviewFilters none() {
        return new ReviewFilters(null, null, null, null, null, null, null);
    }

    public ReviewFilters normalized() {
        return new ReviewFilters(createdFrom, createdTo, tokenId, text(sessionId), text(gameCode),
                notification, hasUserHand, aiResult == AiResultState.ALL ? null : aiResult,
                aiVerdict == AiVerdict.ALL ? null : aiVerdict, confidenceFrom, confidenceTo);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
