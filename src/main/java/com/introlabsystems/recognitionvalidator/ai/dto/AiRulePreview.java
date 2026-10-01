package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.List;

public record AiRulePreview(Instant generatedAt, boolean enabled, List<Item> items, boolean hasMore) {
    public record Item(String imageId, String fileName, String gameCode, Instant createdAt) {}
}
