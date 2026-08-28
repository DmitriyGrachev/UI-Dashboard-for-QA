package com.introlabsystems.recognitionvalidator.model.value;

import com.introlabsystems.recognitionvalidator.model.enums.AdminReviewState;
import com.introlabsystems.recognitionvalidator.model.enums.ImageStorageState;

import java.time.Instant;

public record AdminScreenshotListItem(
        String imageId,
        String fileName,
        Instant fileCreatedAt,
        String gameCode,
        String sessionId,
        AdminReviewState reviewState,
        ImageStorageState storageState
) {
}
