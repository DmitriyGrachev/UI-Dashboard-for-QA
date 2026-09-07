package com.introlabsystems.recognitionvalidator.ai.config;

import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seed retained AI results before the application can accept new completions. */
@Component
@DependsOn("entityManagerFactory")
public class AiDailyStatisticsInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final DailyStatisticsRepository dailyStatistics;
    private final ReviewDisagreementRepository disagreements;
    private final TransactionTemplate transaction;

    public AiDailyStatisticsInitializer(
            JdbcTemplate jdbc,
            DailyStatisticsRepository dailyStatistics,
            ReviewDisagreementRepository disagreements,
            PlatformTransactionManager manager
    ) {
        this.jdbc = jdbc;
        this.dailyStatistics = dailyStatistics;
        this.disagreements = disagreements;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(10);
    }

    @Override
    public void afterPropertiesSet() {
        transaction.executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.execute("SET LOCAL statement_timeout='5s'");
            jdbc.execute("LOCK TABLE ai_review_task IN SHARE ROW EXCLUSIVE MODE");
            jdbc.execute("LOCK TABLE review_task IN SHARE ROW EXCLUSIVE MODE");
            dailyStatistics.rebuildAiFromCompletedTasks();
            disagreements.backfill();
        });
    }
}
