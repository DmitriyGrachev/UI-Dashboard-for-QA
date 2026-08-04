package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewItem;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;
import com.introlabsystems.recognitionvalidator.service.ReviewQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
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
