package com.introlabsystems.recognitionvalidator.model.value;

import com.introlabsystems.recognitionvalidator.model.enums.AdminReviewState;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.enums.ImageStorageState;
import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;

import java.time.Instant;

public record AdminScreenshotDetails(
        String imageId,
        String fileName,
        Instant fileCreatedAt,
        Instant processedAt,
        String gameCode,
        Long tokenId,
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
        boolean surrender,
        Long recognitionDurationMs,
        ParseStatus parseStatus,
        AdminReviewState reviewState,
        Decision decision,
        String reviewedBy,
        Instant reviewedAt,
        ImageStorageState storageState,
        Instant cloudUploadedAt
) {
}
