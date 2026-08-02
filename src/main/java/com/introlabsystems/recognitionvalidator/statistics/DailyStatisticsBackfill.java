package com.introlabsystems.recognitionvalidator.statistics;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DailyStatisticsBackfill implements ApplicationRunner {

    private final DailyStatisticsRepository repository;

    public DailyStatisticsBackfill(DailyStatisticsRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        repository.rebuildFromCompletedTasks();
    }
}
