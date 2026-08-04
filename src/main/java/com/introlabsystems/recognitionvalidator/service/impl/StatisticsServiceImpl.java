package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.service.StatisticsService;
import com.introlabsystems.recognitionvalidator.statistics.DailyReviewCount;
import com.introlabsystems.recognitionvalidator.statistics.OperatorStatistics;
import com.introlabsystems.recognitionvalidator.statistics.StatisticsRepository;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final StatisticsRepository repository;
    private final Clock clock;

    @Override
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
