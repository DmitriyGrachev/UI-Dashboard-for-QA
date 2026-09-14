package com.introlabsystems.recognitionvalidator.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.sql.Timestamp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"validator.integration.statistics-api-key=statistics-test-only",
        "validator.integration.image-api-key=images-test-only"})
class DailyStatisticsApiTest extends AbstractWebIntegrationTest {
    private static final String URL = "/api/integration/statistics/daily";
    @BeforeEach void cleanAiTotals() { jdbc.execute("TRUNCATE ai_daily_statistics"); }

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
                .andExpect(jsonPath("$.date").value(LocalDate.now(ZoneOffset.UTC).toString()));
        mockMvc.perform(get(URL).param("date", "2026-02-30").header("X-API-Key", "statistics-test-only"))
                .andExpect(status().isBadRequest());
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
