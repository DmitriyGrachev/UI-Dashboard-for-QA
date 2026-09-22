package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiFailureSummary(Instant generatedAt, long total, List<Group> groups) {
    public record Group(UUID ruleId, String ruleName, String errorCode, long count) {}
}
