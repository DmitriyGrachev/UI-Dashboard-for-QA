package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.repository.AiOperationsRepository;
import com.introlabsystems.recognitionvalidator.ai.dto.AiOperationsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiOperationsControllerTest {
    @Test
    void exposesTheSharedOperationalSnapshot() {
        Instant now = Instant.parse("2026-09-09T10:00:00Z");
        AiOperationsRepository repository = mock(AiOperationsRepository.class);
        when(repository.snapshot(now)).thenReturn(new AiOperationsSnapshot(
                true, true, 3, 2, 1, now.minusSeconds(30)));

        var response = new AiOperationsController(repository, Clock.fixed(now, ZoneOffset.UTC)).read();

        assertThat(response.getBody()).isEqualTo(new AiOperationsSnapshot(
                true, true, 3, 2, 1, now.minusSeconds(30)));
    }
}
