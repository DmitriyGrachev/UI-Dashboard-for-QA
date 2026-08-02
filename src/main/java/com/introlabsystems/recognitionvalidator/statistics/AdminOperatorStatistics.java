package com.introlabsystems.recognitionvalidator.statistics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminOperatorStatistics(
        UUID operatorId,
        String username,
        boolean enabled,
        Instant createdAt,
        long today,
        long lastSevenDays,
        long allTime,
        long matched,
        long notMatched,
        List<DailyReviewCount> daily,
        int barPercent
) {
    public long maxDaily() {
        return daily.stream().mapToLong(DailyReviewCount::total).max().orElse(0);
    }
}
