package com.introlabsystems.recognitionvalidator.review;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
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
        return claimRepository.claim(
                operatorId,
                filters == null ? ReviewFilters.none() : filters,
                clock.instant(),
                properties.leaseDuration()
        );
    }
}
