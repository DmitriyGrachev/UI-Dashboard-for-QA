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

    @Test
    void equivalentFiltersShareCountsWhileDifferentFiltersStayIndependent() {
        var queue = mock(ReviewQueueService.class);
        var tasks = new ArrayDeque<Runnable>();
        var cache = new ReviewSummaryCache(queue, Clock.systemUTC(), tasks::add);
        var first = new ReviewFilters(null, null, null, "a", null, null, null);
        var spaced = new ReviewFilters(null, null, null, " a ", "", null, null);
        var second = new ReviewFilters(null, null, null, "b", null, null, null);
        when(queue.summarize(null, first)).thenReturn(new ReviewQueueSummary(10, null, null));
        when(queue.summarize(null, second)).thenReturn(new ReviewQueueSummary(20, null, null));
        cache.read(first);
        cache.read(spaced);
        cache.read(second);
        assertThat(tasks).hasSize(2);
        tasks.remove().run();
        assertThat(cache.read(spaced).value().remaining()).isEqualTo(10);
        assertThat(cache.read(second).value()).isNull();
        tasks.remove().run();
        assertThat(cache.read(second).value().remaining()).isEqualTo(20);
        assertThat(cache.read(first).value().remaining()).isEqualTo(10);
        verify(queue).summarize(null, first);
        verify(queue).summarize(null, second);
    }

    @Test
    void ttlStartsAfterQueryCompletionAndAsOfDescribesQueryStart() {
        var queue = mock(ReviewQueueService.class);
        var clock = mock(Clock.class);
        var start = Instant.parse("2026-09-14T10:00:00Z");
        when(clock.instant()).thenReturn(start);
        var tasks = new ArrayDeque<Runnable>();
        var cache = new ReviewSummaryCache(queue, clock, tasks::add);
        when(queue.summarize(null, ReviewFilters.none())).thenAnswer(call -> {
            when(clock.instant()).thenReturn(start.plusSeconds(10));
            return new ReviewQueueSummary(5, null, null);
        });
        cache.read(null);
        tasks.remove().run();
        when(clock.instant()).thenReturn(start.plusMillis(14999));
        assertThat(cache.read(null).asOf()).isEqualTo(start);
        assertThat(cache.read(null).refreshing()).isFalse();
        assertThat(tasks).isEmpty();
        when(clock.instant()).thenReturn(start.plusSeconds(15));
        assertThat(cache.read(null).refreshing()).isTrue();
        assertThat(cache.read(null).value().remaining()).isEqualTo(5);
        assertThat(tasks).hasSize(1);
    }

    @Test
    void capacityEvictsLeastRecentlyUsedCompletedCountButKeepsRecentOne() {
        var queue = mock(ReviewQueueService.class);
        var clock = Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), java.time.ZoneOffset.UTC);
        var tasks = new ArrayDeque<Runnable>();
        var cache = new ReviewSummaryCache(queue, clock, tasks::add);
        when(queue.summarize(any(), any())).thenReturn(new ReviewQueueSummary(1, null, null));
        var filters = java.util.stream.IntStream.range(0, 129)
                .mapToObj(i -> new ReviewFilters(null, null, (long)i, null, null, null, null)).toList();
        for (int i = 0; i < 128; i++) { cache.read(filters.get(i)); tasks.remove().run(); }
        cache.read(filters.get(0));
        cache.read(filters.get(128));
        assertThat(cache.read(filters.get(0)).value().remaining()).isEqualTo(1);
        assertThat(cache.read(filters.get(1)).value()).isNull();
        assertThat(tasks).hasSize(2);
    }
}
