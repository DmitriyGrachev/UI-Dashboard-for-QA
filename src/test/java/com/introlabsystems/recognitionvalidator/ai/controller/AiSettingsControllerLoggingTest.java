package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.AiRule;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AiSettingsControllerLoggingTest {

    @Test
    void savedSettingsLogSummaryWithoutRuleFilters(CapturedOutput output) {
        AiSettingsRepository repository = mock(AiSettingsRepository.class);
        AiRule privateRule = new AiRule(
                UUID.randomUUID(), "Priority", true, 1, "bj_single_deck_ags",
                null, null, 53L, "private-session", null
        );
        AiSettings request = new AiSettings(1, true, List.of(privateRule));
        AiSettings saved = new AiSettings(2, true, List.of(privateRule));
        when(repository.save(request)).thenReturn(saved);
        ValidatorProperties validator = mock(ValidatorProperties.class);
        when(validator.games()).thenReturn(List.of("bj_single_deck_ags", "bj_igt"));
        AiSettingsController controller = new AiSettingsController(repository,
                new AiQueueProperties(Duration.ofMinutes(2)), validator, mock(com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository.class));

        controller.save(request, () -> "audit\nadmin");

        assertThat(output)
                .contains("AI queue settings saved: revision=2, enabled=true, rules=1, enabledRules=1")
                .contains("actor=audit_admin", "order=[1:" + privateRule.id() + ":enabled]")
                .doesNotContain("private-session");
    }

    @Test
    void rejectsRulesForUnknownGames() {
        AiSettingsRepository repository = mock(AiSettingsRepository.class);
        ValidatorProperties validator = mock(ValidatorProperties.class);
        when(validator.games()).thenReturn(List.of("bj_single_deck_ags", "bj_igt"));
        AiSettingsController controller = new AiSettingsController(repository,
                new AiQueueProperties(Duration.ofMinutes(2)), validator, mock(com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository.class));
        AiSettings request = new AiSettings(0, false, List.of(new AiRule(
                UUID.randomUUID(), "Unknown", true, 1, "bj_unknown",
                null, null, null, null, null)));

        assertThatIllegalArgumentException().isThrownBy(() -> controller.save(request, () -> "admin"))
                .withMessageContaining("Unknown game code");
        verify(repository, never()).save(any());
    }

    @Test
    void failedSaveDoesNotLogACommittedConfiguration(CapturedOutput output) {
        var repository = mock(AiSettingsRepository.class);
        var validator = mock(ValidatorProperties.class);
        var request = new AiSettings(3, false, List.of());
        when(repository.save(request)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var controller = new AiSettingsController(repository, new AiQueueProperties(Duration.ofMinutes(2)), validator, mock(com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository.class));
        assertThatThrownBy(() -> controller.save(request, () -> "admin"))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(output).doesNotContain("AI queue settings saved:");
    }
}
