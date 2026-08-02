package com.introlabsystems.recognitionvalidator.statistics;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class StatisticsService {

    private final StatisticsRepository repository;
    private final Clock clock;

    public StatisticsService(StatisticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public OperatorStatistics forOperator(UUID operatorId) {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start = today.minusDays(6);
        StatisticsRepository.StatisticsCounts counts = repository.countForOperator(
                operatorId,
                today,
                start,
                today
        );
        Map<LocalDate, DailyReviewCount> stored = repository.dailyForOperator(
                operatorId,
                start,
                today
        ).stream().collect(Collectors.toMap(DailyReviewCount::date, Function.identity()));
        var daily = IntStream.range(0, 7)
                .mapToObj(start::plusDays)
                .map(date -> stored.getOrDefault(
                        date,
                        new DailyReviewCount(date, 0, 0, 0)
                ))
                .toList();

        return new OperatorStatistics(
                counts.today(),
                counts.lastSevenDays(),
                counts.allTime(),
                counts.matched(),
                counts.notMatched(),
                daily
        );
    }
}
