package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.repository.AiOperationsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

@RestController
@RequestMapping("/admin/api/ai-queue/operations")
@RequiredArgsConstructor
public class AiOperationsController {
    private final AiOperationsRepository operations;
    private final Clock clock;

    @GetMapping("/activity")
    public ResponseEntity<com.introlabsystems.recognitionvalidator.ai.dto.AiRuleActivitySnapshot> activity() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.activity(clock.instant()));
    }

    @GetMapping("/rules")
    public ResponseEntity<AiOperationsRepository.RuleSnapshot> rules() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.rules(clock.instant()));
    }

    @GetMapping
    public ResponseEntity<View> read() {
        var value = operations.snapshot(clock.instant());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new View(
                value.enabled(), value.hasEligiblePending(), value.processing(), value.failed(), value.expired(), value.lastResult()));
    }

    public record View(boolean enabled, boolean hasEligiblePending, long processing, long failed,
                       long expired, Instant lastResult) {}
}
