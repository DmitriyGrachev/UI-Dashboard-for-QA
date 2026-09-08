package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewItem;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueSummary;
import com.introlabsystems.recognitionvalidator.service.ReviewQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewQueueServiceImpl implements ReviewQueueService {

    private final ReviewClaimRepository claimRepository;
    private final ValidatorProperties properties;
    private final Clock clock;

    @Override
    public Optional<ReviewItem> claim(UUID operatorId, ReviewFilters filters) {
        return claim(operatorId, filters, false, false).item();
    }

    @Override
    public ReviewQueueResult claim(
            UUID operatorId,
            ReviewFilters filters,
            boolean replaceCurrent,
            boolean includeRemaining
    ) {
        long started = System.nanoTime();
        ReviewQueueResult result = claimRepository.claim(
                operatorId,
                filters == null ? ReviewFilters.none() : filters,
                clock.instant(),
                properties.leaseDuration(),
                replaceCurrent,
                includeRemaining && properties.countRemainingScreenshots()
        );
        log.debug(
                "Review queue claim completed: operatorId={}, replaceCurrent={}, includeRemaining={}, assigned={}, durationMs={}",
                operatorId,
                replaceCurrent,
                includeRemaining,
                result.item().isPresent(),
                (System.nanoTime() - started) / 1_000_000
        );
        return result;
    }

    @Override
    public ReviewQueueSummary summarize(UUID operatorId, ReviewFilters filters) {
        if (!properties.countRemainingScreenshots()) {
            return new ReviewQueueSummary(0, null, null);
        }
        return claimRepository.summarize(
                operatorId,
                filters == null ? ReviewFilters.none() : filters
        );
    }
}
