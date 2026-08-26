package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;

/**
 * Metadata needed to upload one image without loading its contents into memory.
 */
public record CloudUploadCandidate(
        String imageId,
        String relativePath,
        Instant fileCreatedAt,
        String objectKey,
        Instant nextAttemptAt,
        int attemptCount
) {
}
