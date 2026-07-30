package com.introlabsystems.recognitionvalidator.statistics;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class StatisticsService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_PERCENT = new BigDecimal("0.00");

    private final StatisticsRepository repository;
    private final Clock clock;

    public StatisticsService(StatisticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public OperatorStatistics forOperator(UUID operatorId, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Period start must be before period end");
        }

        LocalDate todayUtc = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        Instant todayStart = todayUtc.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant tomorrowStart = todayUtc.plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        StatisticsRepository.StatisticsCounts counts = repository.countForOperator(
                operatorId,
                todayStart,
                tomorrowStart,
                from,
                to
        );

        return new OperatorStatistics(
                counts.today(),
                counts.retainedTotal(),
                counts.periodTotal(),
                counts.accepted(),
                counts.rejected(),
                percent(counts.accepted(), counts.periodTotal()),
                percent(counts.rejected(), counts.periodTotal())
        );
    }

    private BigDecimal percent(long part, long total) {
        if (total == 0) {
            return ZERO_PERCENT;
        }
        return BigDecimal.valueOf(part)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
