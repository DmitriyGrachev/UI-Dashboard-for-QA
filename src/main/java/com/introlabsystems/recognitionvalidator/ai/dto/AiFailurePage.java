package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiFailurePage(List<Item> items, Instant nextAt, String nextId) {
    public record Item(String imageId, String fileName, String gameCode, UUID ruleId, String ruleName,
                       String errorCode, String errorMessage, Instant failedAt, Instant createdAt, int attemptCount) {}
}
