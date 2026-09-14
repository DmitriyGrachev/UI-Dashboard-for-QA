package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewCandidateBuffer;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.service.ReviewQueueService;
import com.introlabsystems.recognitionvalidator.service.ReviewSummaryCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewSummaryRefreshTest extends AbstractWebIntegrationTest {
    @Autowired ReviewSummaryCache summaries;
    @Autowired ReviewCandidateBuffer candidates;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean ReviewQueueService queue;
    private final Instant now = Instant.parse("2026-09-14T10:00:00Z");
    private OperatorPrincipal operator;

    @BeforeEach void resetSnapshots() {
        summaries.clear();
        candidates.clear();
        doReturn(now).when(clock).instant();
        operator = new OperatorPrincipal(insertOperator("count-reader", "password"), "count-reader", "hash", true);
    }

    @Test
    void cachedZeroRefreshesAfterInsertsAndAgainAfterDeletion() throws Exception {
        summary().andExpect(status().isAccepted()).andExpect(jsonPath("$.remaining").isEmpty())
                .andExpect(jsonPath("$.refreshing").value(true)).andExpect(header().string("Cache-Control", "no-store"));
        expectCount(0);
        String first = insertReviewImage(1, "one.png", true, "bj_igt", "a", null, "Jack", null);
        insertReviewImage(2, "unrelated.png", true, "bj_igt", "b", null, "Jack", null);
        doReturn(now.plusMillis(4999)).when(clock).instant();
        summary().andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.refreshing").value(false));
        doReturn(now.plusSeconds(5)).when(clock).instant();
        summary().andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.refreshing").value(true));
        expectCount(1);
        insertReviewImage(3, "two.png", true, "bj_igt", "a", null, "Jack", null);
        doReturn(now.plusSeconds(10)).when(clock).instant();
        expectCount(2);
        jdbc.update("DELETE FROM image_asset WHERE id=?", first);
        doReturn(now.plusSeconds(15)).when(clock).instant();
        expectCount(1);
        summary().andExpect(jsonPath("$.asOf").value(now.plusSeconds(15).toString()));
    }

    @Test
    void failedInitialCountRetriesAndLaterFailurePreservesLastGoodValue() throws Exception {
        var filter = new ReviewFilters(null, null, null, "a", null, null, null);
        doThrow(new DataAccessResourceFailureException("test database unavailable")).when(queue).summarize(null, filter);
        summary().andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> summary().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.remaining").isEmpty()).andExpect(jsonPath("$.failed").value(true)));
        verify(queue).summarize(null, filter);
        insertReviewImage(1, "one.png", true, "bj_igt", "a", null, "Jack", null);
        doCallRealMethod().when(queue).summarize(null, filter);
        doReturn(now.plusSeconds(5)).when(clock).instant();
        expectCount(1);
        doThrow(new DataAccessResourceFailureException("test database unavailable")).when(queue).summarize(null, filter);
        doReturn(now.plusSeconds(10)).when(clock).instant();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> summary().andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(1)).andExpect(jsonPath("$.failed").value(true)));
        doCallRealMethod().when(queue).summarize(null, filter);
        insertReviewImage(2, "two.png", true, "bj_igt", "a", null, "Jack", null);
        doReturn(now.plusSeconds(15)).when(clock).instant();
        expectCount(2);
    }

    @Test
    void requestsShareOneSlowCountAndClaimsContinueWhileItRuns() throws Exception {
        String id = insertReviewImage(1, "one.png", true, "bj_igt", "a", null, "Jack", null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var filter = new ReviewFilters(null, null, null, "a", null, null, null);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Count was not released");
            return call.callRealMethod();
        }).when(queue).summarize(null, filter);
        try {
            summary().andExpect(status().isAccepted());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) summary().andExpect(status().isAccepted());
            mockMvc.perform(post("/api/review-tasks/claim").with(user(operator)).with(csrf())
                            .contentType("application/json").content("{\"filters\":{\"sessionId\":\"a\"},\"includeRemaining\":false}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.item.imageId").value(id));
            verify(queue).summarize(null, filter);
        } finally { release.countDown(); }
        expectCount(1);
    }

    private ResultActions summary() throws Exception {
        return mockMvc.perform(post("/api/review-tasks/summary").with(user(operator)).with(csrf())
                .contentType("application/json").content("{\"sessionId\":\"a\"}"));
    }

    private void expectCount(long value) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> summary().andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(value)).andExpect(jsonPath("$.refreshing").value(false))
                .andExpect(jsonPath("$.failed").value(false)));
    }
}
