package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewItem;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReviewQueueService {

    private final ReviewClaimRepository claimRepository;
    private final ValidatorProperties properties;
    private final Clock clock;

    public ReviewQueueService(
            ReviewClaimRepository claimRepository,
            ValidatorProperties properties,
            Clock clock
    ) {
        this.claimRepository = claimRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<ReviewItem> claim(UUID operatorId, ReviewFilters filters) {
        return claim(operatorId, filters, false, false).item();
    }

    public ReviewQueueResult claim(
            UUID operatorId,
            ReviewFilters filters,
            boolean replaceCurrent,
            boolean includeRemaining
    ) {
        return claimRepository.claim(
                operatorId,
                filters == null ? ReviewFilters.none() : filters,
                clock.instant(),
                properties.leaseDuration(),
                replaceCurrent,
                includeRemaining && properties.countRemainingScreenshots()
        );
    }
}
