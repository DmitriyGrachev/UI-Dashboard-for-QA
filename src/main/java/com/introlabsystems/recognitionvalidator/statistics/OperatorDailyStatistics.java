package com.introlabsystems.recognitionvalidator.statistics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@IdClass(OperatorDailyStatisticsId.class)
@Table(
        name = "operator_daily_statistics",
        indexes = @Index(
                name = "ix_daily_statistics_date_operator",
                columnList = "statistics_date,operator_id"
        )
)
public class OperatorDailyStatistics {

    @Id
    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Id
    @Column(name = "statistics_date", nullable = false)
    private LocalDate statisticsDate;

    @Column(name = "total_checked", nullable = false)
    private long totalChecked;

    @Column(name = "matched_count", nullable = false)
    private long matchedCount;

    @Column(name = "not_matched_count", nullable = false)
    private long notMatchedCount;

    protected OperatorDailyStatistics() {
    }
}
