package com.introlabsystems.recognitionvalidator.review;

import com.introlabsystems.recognitionvalidator.image.RecognitionResult;

import java.time.Instant;

public record ReviewItem(
        String imageId,
        String fileName,
        String gameCode,
        String sessionId,
        Instant fileCreatedAt,
        RecognitionResult recognition
) {
}
