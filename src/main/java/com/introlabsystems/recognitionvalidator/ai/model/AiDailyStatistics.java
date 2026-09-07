package com.introlabsystems.recognitionvalidator.ai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "ai_daily_statistics")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiDailyStatistics {

    @Id
    @Column(name = "statistics_date", nullable = false)
    private LocalDate statisticsDate;

    @Column(name = "total_checked", nullable = false)
    private long totalChecked;

    @Column(name = "matched_count", nullable = false)
    private long matchedCount;

    @Column(name = "not_matched_count", nullable = false)
    private long notMatchedCount;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;
}
