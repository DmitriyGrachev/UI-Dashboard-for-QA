package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.auth.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.review.DecisionService;
import com.introlabsystems.recognitionvalidator.review.ReviewFilters;
import com.introlabsystems.recognitionvalidator.review.ReviewItem;
import com.introlabsystems.recognitionvalidator.review.ReviewQueueService;
import com.introlabsystems.recognitionvalidator.web.dto.DecisionRequest;
import com.introlabsystems.recognitionvalidator.web.dto.ReviewFilterRequest;
import com.introlabsystems.recognitionvalidator.web.dto.ReviewItemResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/review-tasks")
public class ReviewApiController {

    private final ReviewQueueService queueService;
    private final DecisionService decisionService;

    public ReviewApiController(
            ReviewQueueService queueService,
            DecisionService decisionService
    ) {
        this.queueService = queueService;
        this.decisionService = decisionService;
    }

    @PostMapping("/claim")
    ResponseEntity<ReviewItemResponse> claim(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @Valid @RequestBody(required = false) ReviewFilterRequest request
    ) {
        ReviewFilters filters = request == null ? ReviewFilters.none() : request.toFilters();
        Optional<ReviewItem> item = queueService.claim(principal.id(), filters);
        return item
                .map(ReviewItemResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{imageId}/decision")
    ResponseEntity<Void> decide(
            @PathVariable String imageId,
            @AuthenticationPrincipal OperatorPrincipal principal,
            @Valid @RequestBody DecisionRequest request
    ) {
        decisionService.decide(imageId, principal.id(), request.decision());
        return ResponseEntity.noContent().build();
    }
}
