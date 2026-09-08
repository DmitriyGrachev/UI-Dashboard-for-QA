package com.introlabsystems.recognitionvalidator.ai.dto;

import java.util.UUID;

public record AiReject(String imageId, UUID claimId, String message) {
    public AiReject {
        if (imageId == null || !imageId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid image ID");
        }
        if (claimId == null) {
            throw new IllegalArgumentException("A claimId is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("A nonblank message is required");
        }
        if (message.length() > 1000) {
            throw new IllegalArgumentException("Message exceeds 1000 characters");
        }
    }
}
