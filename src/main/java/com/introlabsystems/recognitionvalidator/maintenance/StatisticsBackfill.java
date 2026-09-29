package com.introlabsystems.recognitionvalidator.maintenance;

import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit offline maintenance command; deliberately absent from component scanning. */
@Slf4j
public class StatisticsBackfill {
    private final JdbcTemplate jdbc;
    private final DailyStatisticsRepository dailyStatistics;
    private final ReviewDisagreementRepository disagreements;
    private final TransactionTemplate transaction;
    private final int timeoutSeconds;

    public StatisticsBackfill(
            JdbcTemplate jdbc,
            DailyStatisticsRepository dailyStatistics,
            ReviewDisagreementRepository disagreements,
            PlatformTransactionManager manager,
            @Value("${validator.statistics.backfill-timeout-seconds:600}") int timeoutSeconds
    ) {
        this.jdbc = jdbc;
        this.dailyStatistics = dailyStatistics;
        this.disagreements = disagreements;
        this.transaction = new TransactionTemplate(manager);
        if (timeoutSeconds < 1 || timeoutSeconds > 86400) throw new IllegalArgumentException("Backfill timeout must be 1–86400 seconds");
        this.timeoutSeconds = timeoutSeconds;
        this.transaction.setTimeout(timeoutSeconds);
    }

    public void execute() {
        transaction.executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.execute("SET LOCAL statement_timeout='" + timeoutSeconds + "s'");
            // Serialize first-time setup across instances without locking either live queue on restart.
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('validator-statistics-bootstrap',0))");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS validator_statistics_bootstrap (
                        name varchar(64) PRIMARY KEY, completed_at timestamptz NOT NULL
                    )
                    """);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM validator_statistics_bootstrap
                                   WHERE name='daily-and-disagreement-v1')
                    """, Boolean.class))) return;
            // Fail fast if another writer is active; run only after stopping all application instances.
            jdbc.execute("LOCK TABLE ai_review_task IN SHARE ROW EXCLUSIVE MODE NOWAIT");
            jdbc.execute("LOCK TABLE review_task IN SHARE ROW EXCLUSIVE MODE NOWAIT");
            log.info("Statistics backfill: filling missing AI daily aggregates");
            dailyStatistics.rebuildAiFromCompletedTasks();
            log.info("Statistics backfill: filling missing operator daily aggregates");
            dailyStatistics.rebuildFromCompletedTasks();
            log.info("Statistics backfill: recording retained AI/operator disagreements");
            disagreements.backfill();
            // Rollback also removes the checkpoint, allowing an interrupted bootstrap to retry.
            jdbc.update("INSERT INTO validator_statistics_bootstrap VALUES ('daily-and-disagreement-v1',clock_timestamp())");
        });
        log.info("Statistics backfill complete (or already completed); checkpoint retained");
    }

    public static void run(String[] args) {
        // Only JDBC configuration: no HTTP server, JPA schema changes, schedulers, watchers or Slack.
        try (var context = new SpringApplicationBuilder(CommandConfiguration.class)
                .web(WebApplicationType.NONE).run(args)) {
            context.getBean(StatisticsBackfill.class).execute();
        }
    }

    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class})
    @Import({StatisticsBackfill.class, DailyStatisticsRepository.class, ReviewDisagreementRepository.class})
    static class CommandConfiguration {}
}
