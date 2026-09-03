package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.dto.AiRule;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AiSettingsRepositoryTest extends AiTestSupport {
    @Autowired AiSettingsRepository settings;

    static AiRule rule(int priority, Long token) {
        return new AiRule(UUID.randomUUID(), "Rule " + priority, true, priority, null, null, token, null, null, null);
    }

    @Test
    void savesRulesAtomicallyAndRejectsLostUpdate() {
        AiSettings initial = settings.read();
        assertThat(initial.enabled()).isFalse();
        assertThat(initial.rules()).isEmpty();
        AiSettings saved = settings.save(new AiSettings(initial.revision(), true, List.of(rule(20, null), rule(10, 53L))));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(settings.read().rules()).extracting(AiRule::priority).containsExactly(10, 20);
        assertThatThrownBy(() -> settings.save(new AiSettings(0, false, List.of()))).hasMessageContaining("SETTINGS_CONFLICT");
        assertThat(settings.read().enabled()).isTrue();
        assertThat(settings.read().rules()).hasSize(2);
    }

    @Test
    void doesNotEnableAnUnconfiguredQueue() {
        assertThatThrownBy(() -> settings.save(new AiSettings(0, true, List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.read().enabled()).isFalse();
    }
}
