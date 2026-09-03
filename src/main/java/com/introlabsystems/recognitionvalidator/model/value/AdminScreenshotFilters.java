package com.introlabsystems.recognitionvalidator.model.value;

import com.introlabsystems.recognitionvalidator.model.enums.AdminReviewState;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.enums.ImageStorageState;
import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;

import java.time.Instant;

public record AdminScreenshotFilters(
        Instant createdFrom,
        Instant createdTo,
        AdminReviewState reviewState,
        String gameCode,
        Long tokenId,
        String sessionId,
        String imageId,
        String fileName,
        Decision decision,
        String reviewedBy,
        ImageStorageState storageState,
        ParseStatus parseStatus,
        Boolean notification,
        Boolean hasUserHand,
        Instant cursorCreatedAt,
        String cursorId,
        int limit,
        com.introlabsystems.recognitionvalidator.ai.model.AiResultState aiResult,
        Integer certaintyFrom,
        Integer certaintyTo
) {
    public AdminScreenshotFilters(Instant createdFrom, Instant createdTo, AdminReviewState reviewState,
                                  String gameCode, Long tokenId, String sessionId, String imageId,
                                  String fileName, Decision decision, String reviewedBy,
                                  ImageStorageState storageState, ParseStatus parseStatus, Boolean notification,
                                  Boolean hasUserHand, Instant cursorCreatedAt, String cursorId, int limit) {
        this(createdFrom, createdTo, reviewState, gameCode, tokenId, sessionId, imageId, fileName,
                decision, reviewedBy, storageState, parseStatus, notification, hasUserHand,
                cursorCreatedAt, cursorId, limit, null, null, null);
    }
}
