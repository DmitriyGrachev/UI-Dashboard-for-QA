package com.introlabsystems.recognitionvalidator.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.introlabsystems.recognitionvalidator.service.DailyStatisticsService;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Clock;
import java.time.ZoneId;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.sql.Timestamp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"validator.integration.statistics-api-key=statistics-test-only",
        "validator.integration.image-api-key=images-test-only"})
class DailyStatisticsApiTest extends AbstractWebIntegrationTest {
    private static final String URL = "/api/integration/statistics/daily";
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean DailyStatisticsService statistics;
    @BeforeEach void cleanAiTotals() {
        jdbc.execute("TRUNCATE ai_daily_statistics");
        doReturn(Instant.parse("2026-09-13T23:59:59Z")).when(clock).instant();
        doReturn(ZoneId.of("Pacific/Kiritimati")).when(clock).getZone();
    }

    @Test
    void emptyDayIsAccessibleOnlyWithTheStatisticsKey() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(user("admin").roles("ADMIN"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).header("X-API-Key", "images-test-only")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("date", "2099-01-01").header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.date").value("2099-01-01"))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.operators.total").value(0))
                .andExpect(jsonPath("$.ai.total").value(0))
                .andExpect(jsonPath("$.ai.confidence.unknown").value(0))
                .andExpect(jsonPath("$.ai.confidence.completeCoverage").value(true));
        mockMvc.perform(get("/api/integration/images/" + "a".repeat(64) + "/content")
                .header("X-API-Key", "statistics-test-only")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("X-API-Key", "statistics-test-only")).andExpect(status().isForbidden());
    }

    @Test
    void dateDefaultsToUtcTodayAndInvalidDatesAreRejected() throws Exception {
        mockMvc.perform(get(URL).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-09-13"));
        doReturn(Instant.parse("2026-09-14T00:00:00Z")).when(clock).instant();
        mockMvc.perform(get(URL).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value("2026-09-14"));
        for (String invalid : new String[]{"2026-02-30", "0000-01-01", "+10000-01-01", "2026-09-14T00:00:00Z", "invalid"})
            mockMvc.perform(get(URL).param("date", invalid).header("X-API-Key", "statistics-test-only"))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void freshRequestsSeeNewResultsAndRetentionDoesNotEraseDailyTotals() throws Exception {
        String date = "2026-09-13";
        mockMvc.perform(get(URL).param("date", date).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ai.total").value(0));
        var operator = insertOperator("operator", "password");
        insertDailyStatistics(operator, LocalDate.parse(date), 1, 1, 0);
        String id = insertReviewImage(1, "one.png", true, "bj_igt", "session", null, "Jack", null);
        jdbc.update("INSERT INTO ai_daily_statistics(statistics_date,total_checked,matched_count,not_matched_count) VALUES (?,1,1,0)", LocalDate.parse(date));
        jdbc.update("""
                INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,is_notification,has_user_hand,
                    attempt_count,file_available,checked_at,valid,confidence)
                VALUES (?,'COMPLETED',?,'bj_igt',false,true,0,true,?,true,99)
                """, id, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        mockMvc.perform(get(URL).param("date", date).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.operators.accepted").value(1))
                .andExpect(jsonPath("$.operators.byOperator[0].id").value(operator.toString()))
                .andExpect(jsonPath("$.operators.byOperator[0].accepted").value(1))
                .andExpect(jsonPath("$.ai.total").value(1))
                .andExpect(jsonPath("$.ai.confidence.from95To100").value(1))
                .andExpect(jsonPath("$.ai.confidence.completeCoverage").value(true));
        jdbc.update("DELETE FROM image_asset WHERE id=?", id);
        mockMvc.perform(get(URL).param("date", date).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operators.accepted").value(1))
                .andExpect(jsonPath("$.ai.total").value(1))
                .andExpect(jsonPath("$.ai.confidence.retainedResults").value(0))
                .andExpect(jsonPath("$.ai.confidence.completeCoverage").value(false));
    }

    @Test
    void databaseFailureReturnsRetryableErrorWithoutDetailsAndNextRequestCanRecover() throws Exception {
        var day = LocalDate.parse("2026-09-11");
        doThrow(new DataAccessResourceFailureException("private database connection details"))
                .doCallRealMethod().when(statistics).read(day);
        mockMvc.perform(get(URL).param("date", day.toString()).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.detail").value("Statistics are temporarily unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
        mockMvc.perform(get(URL).param("date", day.toString()).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ai.total").value(0));
    }

    @Test
    void reportsDailyTotalsAndConfidenceCoverageWithinUtcBoundaries() throws Exception {
        var date = LocalDate.parse("2026-09-13");
        var operator = insertOperator("operator", "password");
        var inactive = insertOperator("inactive", "password");
        jdbc.update("UPDATE app_user SET enabled=false WHERE id=?", inactive);
        insertDailyStatistics(operator, date, 10, 8, 2);
        insertDailyStatistics(inactive, date, 3, 1, 2);
        insertDailyStatistics(operator, date.minusDays(1), 50, 50, 0);
        jdbc.update("INSERT INTO ai_daily_statistics(statistics_date,total_checked,matched_count,not_matched_count) VALUES (?,10,7,3)", date);
        Integer[] values = {0, 49, 50, 79, 80, 94, 95, 100, null, 99, 99};
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (int i = 0; i < values.length; i++) {
            String id = insertReviewImage(500+i, "confidence-"+i+".png", true, "bj_igt", "session", null, "Jack", null);
            Instant checked = i == 9 ? start.minusSeconds(1) : i == 10 ? start.plusSeconds(86400) : start.plusSeconds(i);
            jdbc.update("""
                    INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,is_notification,has_user_hand,
                        attempt_count,file_available,checked_at,valid,confidence)
                    VALUES (?,'COMPLETED',?,'bj_igt',false,true,0,true,?,true,?)
                    """, id, Timestamp.from(start), Timestamp.from(checked), values[i]);
        }
        mockMvc.perform(get(URL).param("date", date.toString()).header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators.total").value(13))
                .andExpect(jsonPath("$.operators.accepted").value(9))
                .andExpect(jsonPath("$.operators.rejected").value(4))
                .andExpect(jsonPath("$.operators.byOperator.length()").value(2))
                .andExpect(jsonPath("$.ai.total").value(10))
                .andExpect(jsonPath("$.ai.matched").value(7))
                .andExpect(jsonPath("$.ai.mismatched").value(3))
                .andExpect(jsonPath("$.ai.confidence.below50").value(2))
                .andExpect(jsonPath("$.ai.confidence.from50To79").value(2))
                .andExpect(jsonPath("$.ai.confidence.from80To94").value(2))
                .andExpect(jsonPath("$.ai.confidence.from95To100").value(2))
                .andExpect(jsonPath("$.ai.confidence.unknown").value(1))
                .andExpect(jsonPath("$.ai.confidence.retainedResults").value(9))
                .andExpect(jsonPath("$.ai.confidence.completeCoverage").value(false));
    }
}
