package com.introlabsystems.recognitionvalidator.review;

import com.introlabsystems.recognitionvalidator.image.ParseStatus;

import java.time.Instant;

public record ReviewItem(
        String imageId,
        String fileName,
        String gameCode,
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
        Instant fileCreatedAt,
        Instant processedAt,
        Long recognitionDurationMs,
        ParseStatus parseStatus
) {
}
