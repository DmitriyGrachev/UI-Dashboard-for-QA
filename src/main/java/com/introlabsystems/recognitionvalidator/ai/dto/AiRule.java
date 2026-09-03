package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.UUID;

public record AiRule(UUID id, String name, boolean enabled, int priority,
                     Instant createdFrom, Instant createdTo, Long tokenId, String sessionId,
                     Boolean notification, Boolean hasUserHand) {
    public AiRule {
        id = id == null ? UUID.randomUUID() : id;
        name = name == null ? "" : name.trim();
        sessionId = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        if (name.isEmpty() || name.length() > 100 || priority < 0) {
            throw new IllegalArgumentException("A rule needs a name (1–100 characters) and nonnegative priority");
        }
        if (sessionId != null && sessionId.length() > 128) throw new IllegalArgumentException("Session is too long");
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new IllegalArgumentException("Created from must precede created to");
        }
    }
}
