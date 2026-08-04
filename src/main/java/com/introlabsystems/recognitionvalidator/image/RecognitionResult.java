package com.introlabsystems.recognitionvalidator.image;

import java.time.Instant;

public record RecognitionResult(
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
        boolean surrender,
        Instant processedAt,
        Long recognitionDurationMs,
        ParseStatus parseStatus
) {

    public static RecognitionResult error() {
        return new RecognitionResult(
                null, null, null, null, null,
                false, false, false, false, false, false,
                null, null, ParseStatus.ERROR
        );
    }
}
