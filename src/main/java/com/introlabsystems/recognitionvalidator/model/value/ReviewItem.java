package com.introlabsystems.recognitionvalidator.model.value;

import com.introlabsystems.recognitionvalidator.model.value.RecognitionResult;

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
