package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.model.value.AdminStatisticsPage;
import com.introlabsystems.recognitionvalidator.model.value.AdminOperatorStatistics;
import com.introlabsystems.recognitionvalidator.model.value.DailyReviewCount;
import com.introlabsystems.recognitionvalidator.security.SecurityConfig;
import com.introlabsystems.recognitionvalidator.security.SecurityFailureHandler;
import com.introlabsystems.recognitionvalidator.service.AdminStatisticsService;
import com.introlabsystems.recognitionvalidator.service.AdminUserService;
import com.introlabsystems.recognitionvalidator.service.RejectedScreenshotExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class)
@Import({SecurityConfig.class, SecurityFailureHandler.class})
class AdminUiRenderingWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminStatisticsService statistics;

    @MockitoBean
    private AdminUserService users;

    @MockitoBean
    private RejectedScreenshotExportService rejectedExports;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void adminNavigationRendersEachSectionAndKeepsRosterOnOperatorsPage() throws Exception {
        when(statistics.page(0)).thenReturn(new AdminStatisticsPage(List.of(), 0, 0, 0));

        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"admin-page\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/screenshots\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/ai-queue\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/rejects\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/css/admin.css")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Current roster")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"ai-queue-form\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"rejected-export-form\""))));

        verify(statistics).page(0);
    }

    @Test
    void taskPagesRenderWithoutLoadingRosterStatistics() throws Exception {
        mockMvc.perform(get("/admin/ai-queue").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"ai-queue-form\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/ai-queue\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/css/admin.css")));

        mockMvc.perform(get("/admin/rejects").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"rejected-export-form\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"rejected-export-status\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/rejects\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/css/admin.css")));

        verifyNoInteractions(statistics);
    }

    @Test
    void operatorRowKeepsTechnicalDetailsChartAndManagementInNativeDisclosures() throws Exception {
        UUID operatorId = UUID.randomUUID();
        var operator = new AdminOperatorStatistics(
                operatorId,
                "reviewer",
                true,
                Instant.parse("2026-09-01T10:00:00Z"),
                3,
                12,
                30,
                8,
                4,
                List.of(new DailyReviewCount(LocalDate.of(2026, 9, 4), 3, 2, 1)),
                100
        );
        when(statistics.page(0)).thenReturn(new AdminStatisticsPage(List.of(operator), 0, 1, 1));

        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<details class=\"operator-detail\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        operatorId.toString())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"admin-daily-chart\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<details class=\"operator-manage\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/admin/operators/" + operatorId + "/password")));
    }

    @Test
    void operatorCannotOpenAdminTaskPages() throws Exception {
        mockMvc.perform(get("/admin/ai-queue").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/review"));
        mockMvc.perform(get("/admin/rejects").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/review"));
    }
}
