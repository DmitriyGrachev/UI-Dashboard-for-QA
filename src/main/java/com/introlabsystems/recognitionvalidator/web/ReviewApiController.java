package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.auth.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.review.ReviewFilters;
import com.introlabsystems.recognitionvalidator.review.ReviewQueueService;
import com.introlabsystems.recognitionvalidator.service.ReviewWorkflowService;
import com.introlabsystems.recognitionvalidator.web.dto.DecisionRequest;
import com.introlabsystems.recognitionvalidator.web.dto.ReviewClaimRequest;
import com.introlabsystems.recognitionvalidator.web.dto.ReviewQueueResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-tasks")
public class ReviewApiController {

    private final ReviewQueueService queueService;
    private final ReviewWorkflowService workflowService;

    public ReviewApiController(
            ReviewQueueService queueService,
            ReviewWorkflowService workflowService
    ) {
        this.queueService = queueService;
        this.workflowService = workflowService;
    }

    @PostMapping("/claim")
    ResponseEntity<ReviewQueueResponse> claim(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @Valid @RequestBody(required = false) ReviewClaimRequest request
    ) {
        ReviewFilters filters = request == null ? ReviewFilters.none() : request.toFilters();
        boolean replaceCurrent = request != null && request.replaceCurrent();
        boolean includeRemaining = request != null && request.includeRemaining();
        return ResponseEntity.ok(ReviewQueueResponse.from(queueService.claim(
                principal.id(),
                filters,
                replaceCurrent,
                includeRemaining
        )));
    }

    @PostMapping("/{imageId}/decision")
    ResponseEntity<ReviewQueueResponse> decide(
            @PathVariable String imageId,
            @AuthenticationPrincipal OperatorPrincipal principal,
            @Valid @RequestBody DecisionRequest request
    ) {
        return ResponseEntity.ok(ReviewQueueResponse.from(
                workflowService.decideAndClaimNext(
                        imageId,
                        principal.id(),
                        request.decision(),
                        request.toFilters()
                )
        ));
    }
}
