package com.introlabsystems.recognitionvalidator.review;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ReviewWorkflowService {

    private final DecisionService decisionService;
    private final ReviewQueueService queueService;

    public ReviewWorkflowService(
            DecisionService decisionService,
            ReviewQueueService queueService
    ) {
        this.decisionService = decisionService;
        this.queueService = queueService;
    }

    public ReviewQueueResult decideAndClaimNext(
            String imageId,
            UUID operatorId,
            Decision decision,
        ReviewFilters filters
    ) {
        decisionService.decide(imageId, operatorId, decision);
        return queueService.claim(operatorId, filters, false, false);
    }
}
