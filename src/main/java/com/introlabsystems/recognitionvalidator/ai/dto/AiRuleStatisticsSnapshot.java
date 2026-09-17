package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.List;

public record AiRuleStatisticsSnapshot(long revision, boolean enabled, Instant generatedAt, List<AiRuleStatistics> rules) {}
