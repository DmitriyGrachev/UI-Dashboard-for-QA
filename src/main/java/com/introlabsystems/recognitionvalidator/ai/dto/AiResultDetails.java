package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;

public record AiResultDetails(String status, Boolean valid, String verdict, Integer certainty,
                              Integer confidence, String message, Instant checkedAt,
                              int attemptCount, String lastErrorCode, String lastErrorMessage, Instant lastErrorAt) {}
