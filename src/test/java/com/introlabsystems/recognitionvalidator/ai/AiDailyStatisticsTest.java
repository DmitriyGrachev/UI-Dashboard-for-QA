package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiDailyStatisticsTest extends AiTestSupport {
    @Autowired
    private DailyStatisticsRepository dailyStatistics;

    @Test
    void backfillDoesNotOverwriteAnAlreadyIncrementedDay() {
        String image = image(61, 53);
        Instant checkedAt = Instant.parse("2026-08-30T23:59:59Z");
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED', valid=TRUE, checked_at=? WHERE image_id=?",
                java.sql.Timestamp.from(checkedAt), image);

        dailyStatistics.incrementAi(checkedAt, false);
        dailyStatistics.rebuildAiFromCompletedTasks();

        assertThat(jdbc.queryForMap("SELECT total_checked, matched_count, not_matched_count "
                        + "FROM ai_daily_statistics WHERE statistics_date='2026-08-30'"))
                .containsEntry("total_checked", 1L)
                .containsEntry("matched_count", 0L)
                .containsEntry("not_matched_count", 1L);
    }
}
