package com.introlabsystems.recognitionvalidator.slack;

import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;

import java.time.Instant;

public record RejectedBacklogItem(
        String fileName,
        String game,
        String session,
        String dealer,
        String activeHand,
        String otherHands,
        boolean notification,
        String buttons,
        ParseStatus parseStatus,
        String operator,
        Instant reviewedAt
) {
}
