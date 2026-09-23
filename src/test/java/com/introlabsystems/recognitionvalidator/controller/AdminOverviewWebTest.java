package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.service.impl.AdminStatisticsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminOverviewWebTest extends AbstractWebIntegrationTest {
    private AdminStatisticsServiceImpl statistics;

    @BeforeEach
    void prepareOverview() {
        jdbc.execute("TRUNCATE ai_daily_statistics");
        statistics = new AdminStatisticsServiceImpl(new NamedParameterJdbcTemplate(jdbc),
                Clock.fixed(Instant.parse("2026-01-01T00:05:00Z"), ZoneId.of("America/Los_Angeles")));
    }

    @Test
    void totalsIncludeEveryOperatorAndRetainedAiCountersWithoutImageHistory() {
        for (int i = 0; i < 12; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO app_user(id, username, password_hash, enabled, role, created_at) VALUES (?, ?, 'unused', ?, 'OPERATOR', now())",
                    id, "operator-" + i, i != 11);
            jdbc.update("INSERT INTO operator_daily_statistics(operator_id,statistics_date,total_checked,matched_count,not_matched_count) VALUES (?, '2026-01-01', 10, 8, 2)", id);
        }
        jdbc.update("INSERT INTO ai_daily_statistics(statistics_date,total_checked,matched_count,not_matched_count) VALUES ('2026-01-01', 90, 72, 18)");
        var result = statistics.overview(7);
        assertThat(result.operatorTotal()).isEqualTo(120);
        assertThat(result.accepted()).isEqualTo(96);
        assertThat(result.rejected()).isEqualTo(24);
        assertThat(result.aiTotal()).isEqualTo(90);
        assertThat(result.matched()).isEqualTo(72);
        assertThat(result.mismatched()).isEqualTo(18);
        assertThat(result.maximum()).isEqualTo(120);
        assertThat(result.percent(result.accepted(), result.operatorTotal())).isEqualTo(80);
        assertThat(result.daily()).hasSize(7);
        assertThat(result.daily().getFirst().date()).isEqualTo(LocalDate.of(2025, 12, 26));
        assertThat(result.daily().getLast().date()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.daily().getFirst().aiTotal()).isZero();
    }

    @Test
    void thirtyDayRangeIncludesBoundaryAndExcludesFutureAndEarlierCounters() {
        jdbc.update("""
                INSERT INTO ai_daily_statistics(statistics_date,total_checked,matched_count,not_matched_count)
                VALUES ('2025-12-02',100,100,0), ('2025-12-03',20,16,4), ('2026-01-01',10,9,1), ('2026-01-02',100,100,0)
                """);
        assertThat(statistics.overview(7).aiTotal()).isEqualTo(10);
        var result = statistics.overview(30);
        assertThat(result.daily()).hasSize(30);
        assertThat(result.daily().getFirst().date()).isEqualTo(LocalDate.of(2025, 12, 3));
        assertThat(result.aiTotal()).isEqualTo(30);
        assertThat(result.matched()).isEqualTo(25);
    }

    @Test
    void emptyOverviewRendersGuidanceAndNoInventedSuccessRate() throws Exception {
        var empty = statistics.overview(7);
        assertThat(empty.empty()).isTrue();
        assertThat(empty.maximum()).isZero();
        assertThat(empty.percent(0, 0)).isZero();
        String html = mockMvc.perform(get("/admin/overview").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("No reviews in this period", "No reviews yet", "Manage AI rules")
                .doesNotContain("NaN", "Infinity", "id=\"ai-queue-stop\"", "th:replace=");
    }

    @Test
    void overviewIsAdminOnlyAndRejectsUnsupportedPeriods() throws Exception {
        mockMvc.perform(get("/admin/overview")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/admin/overview").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/review"));
        mockMvc.perform(get("/admin/overview?days=30").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        for (String days : new String[]{"0", "8", "366", "invalid"}) {
            mockMvc.perform(get("/admin/overview").param("days", days).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
    }
}
