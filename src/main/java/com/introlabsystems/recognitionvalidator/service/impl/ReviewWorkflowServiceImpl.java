package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.review.Decision;
import com.introlabsystems.recognitionvalidator.review.DecisionService;
import com.introlabsystems.recognitionvalidator.review.ReviewFilters;
import com.introlabsystems.recognitionvalidator.review.ReviewQueueResult;
import com.introlabsystems.recognitionvalidator.review.ReviewQueueService;
import com.introlabsystems.recognitionvalidator.service.ReviewWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewWorkflowServiceImpl implements ReviewWorkflowService {

    private final DecisionService decisionService;
    private final ReviewQueueService queueService;

    @Override
    public ReviewQueueResult decideAndClaimNext(
            String imageId,
            UUID operatorId,
            Decision decision,
            ReviewFilters filters
    ) {
        decisionService.decide(imageId, operatorId, decision);
        return queueService.claim(operatorId, filters, false, true);
    }
}
