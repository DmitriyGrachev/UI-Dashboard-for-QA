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
        RecognitionResult recognition
) {
}
