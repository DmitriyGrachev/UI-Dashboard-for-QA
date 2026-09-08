package com.introlabsystems.recognitionvalidator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.introlabsystems.recognitionvalidator.ai.dto.AiClaim;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.dto.AiTask;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.ai.service.AiImageLinkService;
import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.introlabsystems.recognitionvalidator.ai.AiSettingsRepositoryTest.rule;

class AiTaskContractTest {
    private final AiSettingsRepository settings = mock(AiSettingsRepository.class);
    private final AiTaskRepository tasks = mock(AiTaskRepository.class);
    private final AiImageLinkService links = mock(AiImageLinkService.class);
    private final AiQueueService service = new AiQueueService(settings, tasks, links);

    @Test
    void claimUsesFilenameAndDoesNotRequireExpectedPayload() throws Exception {
        Instant expires = Instant.parse("2026-09-08T10:00:00Z");
        AiSettings snapshot = new AiSettings(0, true, List.of(rule(10, null)));
        AiClaim claim = new AiClaim("a".repeat(64), UUID.randomUUID(), "original screenshot.png", expires);
        when(settings.read()).thenReturn(snapshot);
        when(tasks.claim(snapshot, 1)).thenReturn(List.of(claim));
        when(tasks.databaseNow()).thenReturn(Instant.parse("2026-09-08T09:50:00Z"));
        when(links.create(claim.imageId(), expires)).thenReturn(URI.create("https://example.test/image.png"));

        AiTask task = service.claim(1).getFirst();
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(task);

        assertThat(json.path("image_name").asText()).isEqualTo("original screenshot.png");
        assertThat(json.has("expected")).isFalse();
        assertThat(task.imageId()).isEqualTo(claim.imageId());
    }
}
