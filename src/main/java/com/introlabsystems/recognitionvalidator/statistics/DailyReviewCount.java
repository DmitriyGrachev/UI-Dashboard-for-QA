package com.introlabsystems.recognitionvalidator.statistics;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record DailyReviewCount(
        LocalDate date,
        long total,
        long matched,
        long notMatched
) {
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("EEE dd", Locale.ENGLISH);

    public String shortDate() {
        return SHORT_DATE.format(date);
    }

    public int relativePercent(long maximum) {
        if (total == 0 || maximum == 0) {
            return 0;
        }
        return Math.max(8, (int) Math.round(total * 100.0 / maximum));
    }

    public int matchedPercent() {
        return share(matched);
    }

    public int notMatchedPercent() {
        return share(notMatched);
    }

    private int share(long value) {
        return total == 0 ? 0 : (int) Math.round(value * 100.0 / total);
    }
}
