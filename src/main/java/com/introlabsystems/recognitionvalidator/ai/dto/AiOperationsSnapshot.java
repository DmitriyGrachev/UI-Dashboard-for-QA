package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;

public record AiOperationsSnapshot(boolean enabled, boolean hasEligiblePending, long processing, long failed,
                                   long expired, Instant lastResult) {}
