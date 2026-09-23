package com.introlabsystems.recognitionvalidator.model.value;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public record AdminOverviewStatistics(List<Day> daily) {
    public long operatorTotal() { return daily.stream().mapToLong(Day::operatorTotal).sum(); }
    public long accepted() { return daily.stream().mapToLong(Day::accepted).sum(); }
    public long rejected() { return daily.stream().mapToLong(Day::rejected).sum(); }
    public long aiTotal() { return daily.stream().mapToLong(Day::aiTotal).sum(); }
    public long matched() { return daily.stream().mapToLong(Day::matched).sum(); }
    public long mismatched() { return daily.stream().mapToLong(Day::mismatched).sum(); }
    public long maximum() { return daily.stream().mapToLong(day -> Math.max(day.operatorTotal(), day.aiTotal())).max().orElse(0); }
    public boolean empty() { return operatorTotal() == 0 && aiTotal() == 0; }
    public double percent(long value, long total) { return total == 0 ? 0 : value * 100.0 / total; }

    public record Day(LocalDate date, long operatorTotal, long accepted, long rejected,
                      long aiTotal, long matched, long mismatched) {
        public String label() { return date.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)); }
    }
}
