package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.model.value.DailyReviewCount;
import com.introlabsystems.recognitionvalidator.model.value.OperatorStatistics;
import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.security.SecurityConfig;
import com.introlabsystems.recognitionvalidator.security.SecurityFailureHandler;
import com.introlabsystems.recognitionvalidator.service.StatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ReviewPageController.class, StatisticsController.class, ReviewHistoryController.class})
@Import({SecurityConfig.class, SecurityFailureHandler.class})
class OperatorUiRenderingWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean ValidatorProperties properties;
    @MockitoBean StatisticsService statisticsService;
    @MockitoBean com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewHistoryRepository reviewHistory;
    @MockitoBean UserDetailsService users;
    private final OperatorPrincipal operator = new OperatorPrincipal(new UUID(0, 1), "Olena", "unused", true);

    @Test
    void reviewRendersOperatorNavigationWithoutFetchingStatisticsOrClaimingTasks() throws Exception {
        when(properties.games()).thenReturn(List.of("bj_igt", "bj_netent"));
        when(properties.countRemainingScreenshots()).thenReturn(true);
        String html = mvc.perform(get("/review").with(user(operator))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        fixture("review.html", html);
        assertThat(html).contains("/css/operator.css", "Olena", "aria-current=\"page\"", "id=\"accept-button\"",
                "id=\"remaining-count\"", "name=\"_csrf\"", "id=\"faq-dialog\"", "data-card-presentation")
                .doesNotContain("th:replace=", "href=\"/admin");
        verifyNoInteractions(statisticsService);
    }

    @Test
    void statisticsRenderActualOperatorCountsAndAccessibleEmptyState() throws Exception {
        var daily = java.util.stream.IntStream.range(0, 7).mapToObj(i ->
                new DailyReviewCount(LocalDate.of(2026, 9, 19).plusDays(i), 100 + i * 10, 90 + i * 10, 10)).toList();
        when(statisticsService.forOperator(operator.id())).thenReturn(new OperatorStatistics(160, 910, 4320, 840, 70, daily));
        String html = mvc.perform(get("/statistics").with(user(operator))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        fixture("statistics.html", html);
        assertThat(html).contains("Olena", "910", "840", "70", "View daily values", "name=\"_csrf\"");
        when(statisticsService.forOperator(operator.id())).thenReturn(new OperatorStatistics(0, 0, 0, 0, 0,
                daily.stream().map(day -> new DailyReviewCount(day.date(), 0, 0, 0)).toList()));
        String empty = mvc.perform(get("/statistics").with(user(operator))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        fixture("statistics-empty.html", empty);
        assertThat(empty).doesNotContain("NaN", "Infinity", "daily-chart-frame")
                .contains("No reviews in the last 7 days", "Back to review");
    }

    @Test
    void operatorPagesStillRequireSignIn() throws Exception {
        for (String path : List.of("/review", "/statistics", "/history")) {
            mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        }
    }

    @Test
    void historyFailureShowsRetryWithoutPretendingTheHistoryIsEmpty() throws Exception {
        when(reviewHistory.recent(operator.id(), null, null)).thenThrow(new org.springframework.dao.QueryTimeoutException("fixture timeout"));
        String html = mvc.perform(get("/history").with(user(operator))).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("History is temporarily unavailable", "Retry", "Back to review").doesNotContain("No reviews to show", "fixture timeout");
        fixture("review-history-error.html", html);
    }

    private void fixture(String name, String html) throws Exception {
        if (!Boolean.getBoolean("validator.write-browser-fixtures")) return;
        Path directory = Path.of("target", "browser-fixtures");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), html);
        for (String asset : List.of("flatpickr.min.js", "flatpickr.min.css")) {
            var resource = new org.springframework.core.io.ClassPathResource("META-INF/resources/webjars/flatpickr/4.6.13/dist/" + asset);
            try (var input = resource.getInputStream()) {
                Files.copy(input, directory.resolve(asset), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
