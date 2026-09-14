package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueSummary;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReviewSummaryCacheTest {
    @Test
    void sharesOneRefreshAndReturnsOldSnapshotDuringRefreshAndFailure() {
        var queue = mock(ReviewQueueService.class);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-09-14T10:00:00Z");
        when(clock.instant()).thenReturn(now);
        var tasks = new ArrayDeque<Runnable>();
        var cache = new ReviewSummaryCache(queue, clock, tasks::add);
        when(queue.summarize(null, ReviewFilters.none())).thenReturn(new ReviewQueueSummary(10, now, now));
        assertThat(cache.read(null).value()).isNull();
        for (int i = 0; i < 20; i++) assertThat(cache.read(ReviewFilters.none()).refreshing()).isTrue();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        assertThat(cache.read(null).value().remaining()).isEqualTo(10);
        assertThat(cache.read(null).asOf()).isEqualTo(now);
        assertThat(tasks).isEmpty();

        when(clock.instant()).thenReturn(now.plusSeconds(6));
        when(queue.summarize(null, ReviewFilters.none())).thenThrow(new IllegalStateException("offline"));
        assertThat(cache.read(null).value().remaining()).isEqualTo(10);
        tasks.remove().run();
        assertThat(cache.read(null).failed()).isTrue();
        assertThat(cache.read(null).value().remaining()).isEqualTo(10);
        assertThat(tasks).isEmpty();
        verify(queue, times(2)).summarize(null, ReviewFilters.none());
    }

    @Test
    void boundsOutstandingCountsEvenWithManyDistinctFilters() {
        var queue = mock(ReviewQueueService.class);
        var tasks = new ArrayDeque<Runnable>();
        var cache = new ReviewSummaryCache(queue, Clock.systemUTC(), tasks::add);
        for (int i = 0; i < 300; i++) {
            cache.read(new ReviewFilters(null, null, (long)i, null, null, null, null));
        }
        assertThat(tasks).hasSize(128);
        verifyNoInteractions(queue);
    }
}
