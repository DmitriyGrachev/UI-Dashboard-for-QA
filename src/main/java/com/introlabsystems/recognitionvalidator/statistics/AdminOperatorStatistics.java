package com.introlabsystems.recognitionvalidator.statistics;

import java.time.Instant;
import java.util.UUID;

public record AdminOperatorStatistics(
        UUID operatorId,
        String username,
        boolean enabled,
        Instant createdAt,
        long today,
        long total,
        long accepted,
        long rejected,
        int barPercent
) {
}
