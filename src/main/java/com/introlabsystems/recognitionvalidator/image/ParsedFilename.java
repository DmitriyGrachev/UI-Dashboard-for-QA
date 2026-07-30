package com.introlabsystems.recognitionvalidator.image;

import java.time.Instant;
import java.util.UUID;

public record ParsedFilename(
        String gameCode,
        Long tokenId,
        UUID sessionUuid,
        String sessionId,
        String dealerCards,
        String activeUserCards,
        String inactiveUserCards,
        String payloadRaw,
        String buttonsRaw,
        boolean notification,
        boolean stand,
        boolean hit,
        boolean doubleAction,
        boolean split,
        Instant processedAt,
        Long recognitionDurationMs,
        ParseStatus parseStatus
) {

    public static ParsedFilename error() {
        return new ParsedFilename(
                null, null, null, null,
                null, null, null, null, null,
                false, false, false, false, false,
                null, null, ParseStatus.ERROR
        );
    }
}
