package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.maintenance.StatisticsBackfill;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StatisticsBootstrapTest extends AiTestSupport {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
    private DailyStatisticsRepository daily;
    private ReviewDisagreementRepository disagreements;

    @Test
    void normalStartupDoesNotRegisterABackfillRunner() {
        assertThat(context.containsBean("aiDailyStatisticsInitializer")).isFalse();
        assertThat(context.getBeansOfType(StatisticsBackfill.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT to_regclass('validator_statistics_bootstrap')", String.class)).isNull();
    }

    @BeforeEach
    void resetCheckpoint() {
        jdbc.execute("DROP TABLE IF EXISTS validator_statistics_bootstrap");
        daily = new DailyStatisticsRepository(new NamedParameterJdbcTemplate(jdbc));
        disagreements = new ReviewDisagreementRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    @Test
    void restartDoesNotScanHistoryOrLockTaskTablesAgain() {
        var observedJdbc = spy(new JdbcTemplate(jdbc.getDataSource()));
        var observedDaily = spy(daily);
        var observedDisagreements = spy(disagreements);
        var initializer = new StatisticsBackfill(observedJdbc, observedDaily, observedDisagreements, transactionManager, 10);
        initializer.execute();
        clearInvocations(observedJdbc, observedDaily, observedDisagreements);

        // A fresh initializer represents another application process against the same database.
        new StatisticsBackfill(observedJdbc, observedDaily, observedDisagreements, transactionManager, 10).execute();

        verifyNoInteractions(observedDaily, observedDisagreements);
        verify(observedJdbc, never()).execute(startsWith("LOCK TABLE"));
    }

    @Test
    void failedBackfillCanBeRetriedAndOnlySuccessfulRunIsRemembered() {
        var observedDaily = spy(daily);
        var observedDisagreements = spy(disagreements);
        doThrow(new IllegalStateException("injected backfill failure")).doCallRealMethod().when(observedDisagreements).backfill();
        var initializer = new StatisticsBackfill(jdbc, observedDaily, observedDisagreements, transactionManager, 10);

        assertThatThrownBy(initializer::execute).hasMessage("injected backfill failure");
        initializer.execute();
        initializer.execute();

        verify(observedDaily, times(2)).rebuildAiFromCompletedTasks();
        verify(observedDaily, times(2)).rebuildFromCompletedTasks();
        verify(observedDisagreements, times(2)).backfill();
    }

    @Test
    void commandRunsAgainstExplicitTestDatabaseAndExitsWithoutStartingTheWebApplication() {
        String image = image(900, 1);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED', valid=TRUE, checked_at='2026-09-01T00:00:00Z' WHERE image_id=?", image);
        com.introlabsystems.recognitionvalidator.RecognitionValidatorApplication.main(new String[]{
                "--backfill-statistics", "--spring.profiles.active=test",
                "--spring.datasource.url=jdbc:postgresql://localhost:5433/recognition_validator_test",
                "--spring.datasource.username=validator", "--spring.datasource.password=validator"});
        assertThat(jdbc.queryForObject("SELECT total_checked FROM ai_daily_statistics WHERE statistics_date='2026-09-01'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM validator_statistics_bootstrap", Long.class)).isEqualTo(1);
    }

    @Test
    void activeWriterRejectsMaintenanceWithoutLeavingCheckpoint() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("LOCK TABLE review_task IN ROW EXCLUSIVE MODE");
            var backfill = new StatisticsBackfill(jdbc, daily, disagreements, transactionManager, 10);
            assertThatThrownBy(backfill::execute).isInstanceOf(org.springframework.dao.DataAccessException.class);
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT to_regclass('validator_statistics_bootstrap')", String.class)).isNull();
    }
}
