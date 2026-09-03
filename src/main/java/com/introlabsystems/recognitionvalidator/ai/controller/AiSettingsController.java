package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRule;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController @RequestMapping("/admin/api/ai-queue/settings") @RequiredArgsConstructor
public class AiSettingsController {
    private final AiSettingsRepository settings;
    private final AiQueueProperties properties;

    @GetMapping
    public ResponseEntity<View> read() { return response(settings.read()); }

    @PutMapping
    public ResponseEntity<View> save(@RequestBody AiSettings requested) { return response(settings.save(requested)); }

    private ResponseEntity<View> response(AiSettings value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new View(value.revision(), value.enabled(),
                value.rules(), properties.leaseDuration().toSeconds(), "bj_single_deck_ags", "SINGLE_DECK"));
    }
    public record View(long revision, boolean enabled, List<AiRule> rules, long leaseSeconds, String sourceGame, String game) {}
}
