package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRule;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.security.Principal;

@RestController
@RequestMapping("/admin/api/ai-queue/settings")
@RequiredArgsConstructor
@Slf4j
public class AiSettingsController {
    private final AiSettingsRepository settings;
    private final AiQueueProperties properties;
    private final ValidatorProperties validatorProperties;

    @GetMapping
    public ResponseEntity<View> read() { return response(settings.read()); }

    @PutMapping
    public ResponseEntity<View> save(@RequestBody AiSettings requested, Principal principal) {
        if (requested.rules().stream().anyMatch(rule -> !validatorProperties.games().contains(rule.gameCode()))) {
            throw new IllegalArgumentException("Unknown game code");
        }
        AiSettings saved = settings.save(requested);
        log.info(
                "AI queue settings saved: revision={}, enabled={}, rules={}, enabledRules={}, actor={}, order={}",
                saved.revision(),
                saved.enabled(),
                saved.rules().size(),
                saved.rules().stream().filter(AiRule::enabled).count(),
                principal.getName().replaceAll("[\\p{Cntrl}]", "_"),
                saved.rules().stream().map(rule -> rule.priority() + ":" + rule.id()
                        + ":" + (rule.enabled() ? "enabled" : "disabled")).toList()
        );
        return response(saved);
    }

    private ResponseEntity<View> response(AiSettings value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new View(value.revision(), value.enabled(),
                value.rules(), properties.leaseDuration().toSeconds(), List.copyOf(validatorProperties.games())));
    }
    public record View(long revision, boolean enabled, List<AiRule> rules, long leaseSeconds, List<String> games) {}
}
