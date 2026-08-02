package com.introlabsystems.recognitionvalidator.statistics;

import java.util.List;

public record OperatorStatistics(
        long today,
        long lastSevenDays,
        long allTime,
        long matched,
        long notMatched,
        List<DailyReviewCount> daily
) {
    public long maxDaily() {
        return daily.stream().mapToLong(DailyReviewCount::total).max().orElse(0);
    }
}
