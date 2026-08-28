package com.introlabsystems.recognitionvalidator.dto.response;

import com.introlabsystems.recognitionvalidator.model.enums.AdminReviewState;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.enums.ImageStorageState;
import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotDetails;

import java.time.Instant;

public record AdminScreenshotDetailsResponse(
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
        Instant cloudUploadedAt,
        String imageUrl,
        String downloadUrl,
        String availabilityUrl,
        String temporaryLinkUrl
) {

    public static AdminScreenshotDetailsResponse from(AdminScreenshotDetails details) {
        String baseUrl = "/admin/api/screenshots/" + details.imageId();
        return new AdminScreenshotDetailsResponse(
                details.imageId(),
                details.fileName(),
                details.fileCreatedAt(),
                details.processedAt(),
                details.gameCode(),
                details.tokenId(),
                details.sessionId(),
                details.dealerCards(),
                details.activeUserCards(),
                details.inactiveUserCards(),
                details.payloadRaw(),
                details.buttonsRaw(),
                details.notification(),
                details.stand(),
                details.hit(),
                details.doubleAction(),
                details.split(),
                details.surrender(),
                details.recognitionDurationMs(),
                details.parseStatus(),
                details.reviewState(),
                details.decision(),
                details.reviewedBy(),
                details.reviewedAt(),
                details.storageState(),
                details.cloudUploadedAt(),
                baseUrl + "/content",
                baseUrl + "/download",
                baseUrl + "/availability",
                baseUrl + "/temporary-link"
        );
    }
}
