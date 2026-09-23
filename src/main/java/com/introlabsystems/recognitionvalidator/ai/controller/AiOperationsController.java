package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.repository.AiOperationsRepository;
import com.introlabsystems.recognitionvalidator.ai.dto.AiOperationsSnapshot;
import com.introlabsystems.recognitionvalidator.ai.dto.AiFailureSummary;
import com.introlabsystems.recognitionvalidator.ai.dto.AiFailurePage;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleActivitySnapshot;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleStatisticsSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;

@RestController
@RequestMapping("/admin/api/ai-queue/operations")
@RequiredArgsConstructor
public class AiOperationsController {
    private final AiOperationsRepository operations;
    private final Clock clock;

    @GetMapping("/failures/tasks")
    public ResponseEntity<AiFailurePage> failureLog(@RequestParam(defaultValue = "false") boolean aiOnly,
                                                   @RequestParam(required = false) Instant beforeAt,
                                                   @RequestParam(required = false) String beforeId) {
        if ((beforeAt == null) != (beforeId == null) || (beforeId != null && !beforeId.matches("[0-9a-f]{64}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide both cursor time and image ID");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.failureLog(aiOnly, beforeAt, beforeId));
    }

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
