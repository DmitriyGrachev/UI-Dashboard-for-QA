package com.introlabsystems.recognitionvalidator.ai.dto;

import java.util.UUID;

public record AiRuleStatistics(UUID ruleId, long remaining, long processing, long completed, long failed) {}
