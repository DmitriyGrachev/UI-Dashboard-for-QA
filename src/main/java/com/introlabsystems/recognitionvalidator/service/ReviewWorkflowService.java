package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.review.Decision;
import com.introlabsystems.recognitionvalidator.review.ReviewFilters;
import com.introlabsystems.recognitionvalidator.review.ReviewQueueResult;

import java.util.UUID;

public interface ReviewWorkflowService {

    ReviewQueueResult decideAndClaimNext(
            String imageId,
            UUID operatorId,
            Decision decision,
            ReviewFilters filters
    );
}
