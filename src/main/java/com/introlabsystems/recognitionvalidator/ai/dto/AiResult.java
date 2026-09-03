package com.introlabsystems.recognitionvalidator.ai.dto;

import java.util.Set;
import java.util.UUID;

public record AiResult(UUID claimId, Boolean valid, String verdict, Integer certainty, Integer confidence, String message) {
    private static final Set<String> VERDICTS = Set.of("MATCH", "LOW_CONFIDENCE", "MISMATCH", "HAND_COUNT_MISMATCH", "NO_HANDS_FOUND");
    public AiResult {
        if (claimId == null || valid == null || verdict == null || !VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("A claimId, boolean valid and supported verdict are required");
        }
        if (valid != verdict.equals("MATCH")) throw new IllegalArgumentException("valid must agree with verdict");
        if (certainty != null && (certainty < 0 || certainty > 100)
                || confidence != null && (confidence < 0 || confidence > 100)) {
            throw new IllegalArgumentException("Certainty and confidence must be integers between 0 and 100");
        }
        if (message != null && message.length() > 2000) throw new IllegalArgumentException("Message exceeds 2000 characters");
    }
}
