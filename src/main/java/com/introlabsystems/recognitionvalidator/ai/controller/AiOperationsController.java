package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.repository.AiOperationsRepository;
import com.introlabsystems.recognitionvalidator.ai.dto.AiOperationsSnapshot;
import com.introlabsystems.recognitionvalidator.ai.dto.AiFailureSummary;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleActivitySnapshot;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleStatisticsSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/admin/api/ai-queue/operations")
@RequiredArgsConstructor
public class AiOperationsController {
    private final AiOperationsRepository operations;
    private final Clock clock;

    @GetMapping("/failures")
    public ResponseEntity<AiFailureSummary> failures() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.failures(clock.instant()));
    }

    @GetMapping("/activity")
    public ResponseEntity<AiRuleActivitySnapshot> activity() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.activity(clock.instant()));
    }

    @GetMapping("/rules")
    public ResponseEntity<AiRuleStatisticsSnapshot> rules() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.rules(clock.instant()));
    }

    @GetMapping
    public ResponseEntity<AiOperationsSnapshot> read() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.snapshot(clock.instant()));
    }
}
