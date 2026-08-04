package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;
import com.introlabsystems.recognitionvalidator.service.impl.ReviewQueueService;
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
