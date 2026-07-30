package com.introlabsystems.recognitionvalidator.image;

import java.time.Instant;
import java.util.UUID;

public record ImageMetadata(
        String id,
        String fileName,
        String relativePath,
        Instant fileCreatedAt,
        Instant fileModifiedAt,
        Instant discoveredAt,
        Instant lastSeenAt,
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
}
