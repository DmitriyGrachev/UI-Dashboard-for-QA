package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewItem;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;

import java.util.Optional;
import java.util.UUID;

public interface ReviewQueueService {

    Optional<ReviewItem> claim(UUID operatorId, ReviewFilters filters);

    ReviewQueueResult claim(
            UUID operatorId,
            ReviewFilters filters,
            boolean replaceCurrent,
            boolean includeRemaining
    );
}
