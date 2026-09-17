package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.UUID;

public record AiRuleActivity(UUID ruleId, Instant lastIssuedAt, Integer lastIssuedCount, Instant lastResultAt,
                             long expired, Instant oldestDeadline, String expiredImageId,
                             Instant lastErrorAt, String lastErrorImageId, String lastErrorCode, String lastErrorMessage) {}
