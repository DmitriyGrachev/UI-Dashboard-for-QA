package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRule;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class AiSettingsControllerLoggingTest {

    @Test
    void savedSettingsLogSummaryWithoutRuleFilters(CapturedOutput output) {
        AiSettingsRepository repository = mock(AiSettingsRepository.class);
        AiRule privateRule = new AiRule(
                UUID.randomUUID(), "Priority", true, 10,
                null, null, 53L, "private-session", null, null
        );
        AiSettings request = new AiSettings(1, true, List.of(privateRule));
        AiSettings saved = new AiSettings(2, true, List.of(privateRule));
        when(repository.save(request)).thenReturn(saved);
        AiSettingsController controller = new AiSettingsController(
                repository,
                new AiQueueProperties(Duration.ofMinutes(2))
        );

        controller.save(request);

        assertThat(output)
                .contains("AI queue settings saved: revision=2, enabled=true, rules=1, enabledRules=1")
                .doesNotContain("private-session");
    }
}
