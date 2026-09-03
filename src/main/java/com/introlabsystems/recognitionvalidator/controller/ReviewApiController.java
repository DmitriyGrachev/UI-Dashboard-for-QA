package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;

import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.service.ReviewQueueService;
import com.introlabsystems.recognitionvalidator.service.ReviewWorkflowService;
import com.introlabsystems.recognitionvalidator.dto.request.DecisionRequest;
import com.introlabsystems.recognitionvalidator.dto.request.ReviewClaimRequest;
import com.introlabsystems.recognitionvalidator.dto.request.ReviewFilterRequest;
import com.introlabsystems.recognitionvalidator.dto.response.ReviewQueueResponse;
import com.introlabsystems.recognitionvalidator.dto.response.ReviewQueueSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-tasks")
@RequiredArgsConstructor
public class ReviewApiController {

    private final ReviewQueueService queueService;
    private final ReviewWorkflowService workflowService;
    private final AiQueueService aiQueue;

    private ReviewQueueResponse response(ReviewQueueResult result) {
        return ReviewQueueResponse.from(result, result.item().map(item -> aiQueue.details(item.imageId())).orElse(null));
    }

    @PostMapping("/claim")
    ResponseEntity<ReviewQueueResponse> claim(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @Valid @RequestBody(required = false) ReviewClaimRequest request
    ) {
        ReviewFilters filters = request == null ? ReviewFilters.none() : request.toFilters();
        boolean replaceCurrent = request != null && request.replaceCurrent();
        boolean includeRemaining = request != null && request.includeRemaining();
        return ResponseEntity.ok(response(queueService.claim(
                principal.id(),
                filters,
                replaceCurrent,
                includeRemaining
        )));
    }

    @PostMapping("/summary")
    ResponseEntity<ReviewQueueSummaryResponse> summary(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @Valid @RequestBody(required = false) ReviewFilterRequest request
    ) {
        ReviewFilters filters = request == null ? ReviewFilters.none() : request.toFilters();
        return ResponseEntity.ok(ReviewQueueSummaryResponse.from(
                queueService.summarize(principal.id(), filters)
        ));
    }

    @PostMapping("/{imageId}/decision")
    ResponseEntity<ReviewQueueResponse> decide(
            @PathVariable String imageId,
            @AuthenticationPrincipal OperatorPrincipal principal,
            @Valid @RequestBody DecisionRequest request
    ) {
        return ResponseEntity.ok(response(
                workflowService.decideAndClaimNext(
                        imageId,
                        principal.id(),
                        request.decision(),
                        request.toFilters()
                )
        ));
    }
}
