package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewQueueServiceTest {

    @Test
    void configurationDisablesRemainingCountEvenWhenTheClientRequestsIt() {
        ReviewClaimRepository repository = mock(ReviewClaimRepository.class);
        UUID operatorId = UUID.randomUUID();
        ReviewFilters filters = ReviewFilters.none();
        Instant now = Instant.parse("2026-08-02T09:00:00Z");
        Duration leaseDuration = Duration.ofMinutes(30);
        ValidatorProperties properties = new ValidatorProperties(
                Path.of("screenshots"),
                List.of("bj_igt"),
                1_000,
                leaseDuration,
                Duration.ofDays(7),
                5_000,
                20,
                true,
                Duration.ofSeconds(2),
                50_000,
                false
        );
        when(repository.claim(
                operatorId,
                filters,
                now,
                leaseDuration,
                false,
                false
        )).thenReturn(new ReviewQueueResult(Optional.empty(), null));
        ReviewQueueService service = new ReviewQueueService(
                repository,
                properties,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.claim(operatorId, filters, false, true);

        verify(repository).claim(
                operatorId,
                filters,
                now,
                leaseDuration,
                false,
                false
        );
    }
}
