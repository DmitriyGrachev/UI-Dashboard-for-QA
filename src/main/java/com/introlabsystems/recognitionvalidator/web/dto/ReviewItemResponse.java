package com.introlabsystems.recognitionvalidator.web.dto;

import com.introlabsystems.recognitionvalidator.image.ParseStatus;
import com.introlabsystems.recognitionvalidator.review.ReviewItem;

import java.time.Instant;

public record ReviewItemResponse(
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
        ParseStatus parseStatus,
        String imageUrl
) {

    public static ReviewItemResponse from(ReviewItem item) {
        return new ReviewItemResponse(
                item.imageId(),
                item.fileName(),
                item.gameCode(),
                item.sessionId(),
                item.dealerCards(),
                item.activeUserCards(),
                item.inactiveUserCards(),
                item.payloadRaw(),
                item.buttonsRaw(),
                item.notification(),
                item.stand(),
                item.hit(),
                item.doubleAction(),
                item.split(),
                item.fileCreatedAt(),
                item.processedAt(),
                item.recognitionDurationMs(),
                item.parseStatus(),
                "/api/images/" + item.imageId() + "/content"
        );
    }
}
