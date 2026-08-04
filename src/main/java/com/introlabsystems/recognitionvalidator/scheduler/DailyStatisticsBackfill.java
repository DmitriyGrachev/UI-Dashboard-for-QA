package com.introlabsystems.recognitionvalidator.scheduler;

import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DailyStatisticsBackfill implements ApplicationRunner {

    private final DailyStatisticsRepository repository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        repository.rebuildFromCompletedTasks();
    }
}
