package com.introlabsystems.recognitionvalidator.dto.response;

import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;
import com.introlabsystems.recognitionvalidator.model.value.ReviewItem;

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
        boolean surrender,
        Instant fileCreatedAt,
        Instant processedAt,
        Long recognitionDurationMs,
        ParseStatus parseStatus,
        String imageUrl
) {

    public static ReviewItemResponse from(ReviewItem item) {
        var recognition = item.recognition();
        return new ReviewItemResponse(
                item.imageId(),
                item.fileName(),
                item.gameCode(),
                item.sessionId(),
                recognition.dealerCards(),
                recognition.activeUserCards(),
                recognition.inactiveUserCards(),
                recognition.payloadRaw(),
                recognition.buttonsRaw(),
                recognition.notification(),
                recognition.stand(),
                recognition.hit(),
                recognition.doubleAction(),
                recognition.split(),
                recognition.surrender(),
                item.fileCreatedAt(),
                recognition.processedAt(),
                recognition.recognitionDurationMs(),
                recognition.parseStatus(),
                "/api/images/" + item.imageId() + "/content"
        );
    }
}
