package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.config.AiDailyStatisticsInitializer;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StatisticsBootstrapTest extends AiTestSupport {
    private DailyStatisticsRepository daily;
    private ReviewDisagreementRepository disagreements;

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
        var initializer = new AiDailyStatisticsInitializer(observedJdbc, observedDaily, observedDisagreements, transactionManager);
        initializer.afterPropertiesSet();
        clearInvocations(observedJdbc, observedDaily, observedDisagreements);

        // A fresh initializer represents another application process against the same database.
        new AiDailyStatisticsInitializer(observedJdbc, observedDaily, observedDisagreements, transactionManager).afterPropertiesSet();

        verifyNoInteractions(observedDaily, observedDisagreements);
        verify(observedJdbc, never()).execute(startsWith("LOCK TABLE"));
    }

    @Test
    void failedBackfillCanBeRetriedAndOnlySuccessfulRunIsRemembered() {
        var observedDaily = spy(daily);
        var observedDisagreements = spy(disagreements);
        doThrow(new IllegalStateException("injected backfill failure")).doCallRealMethod().when(observedDisagreements).backfill();
        var initializer = new AiDailyStatisticsInitializer(jdbc, observedDaily, observedDisagreements, transactionManager);

        assertThatThrownBy(initializer::afterPropertiesSet).hasMessage("injected backfill failure");
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        verify(observedDaily, times(2)).rebuildAiFromCompletedTasks();
        verify(observedDaily, times(2)).rebuildFromCompletedTasks();
        verify(observedDisagreements, times(2)).backfill();
    }
}
