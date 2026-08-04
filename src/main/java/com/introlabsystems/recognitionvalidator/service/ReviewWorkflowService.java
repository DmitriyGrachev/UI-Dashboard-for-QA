package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;

import java.util.UUID;

public interface ReviewWorkflowService {

    ReviewQueueResult decideAndClaimNext(
            String imageId,
            UUID operatorId,
            Decision decision,
            ReviewFilters filters
    );
}
