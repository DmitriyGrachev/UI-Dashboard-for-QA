package com.introlabsystems.recognitionvalidator.ai.service;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import com.introlabsystems.recognitionvalidator.ai.mapper.AiCardPayloadMapper;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service @RequiredArgsConstructor @Slf4j
public class AiQueueService {
    private final AiSettingsRepository settings;
    private final AiTaskRepository tasks;
    private final AiCardPayloadMapper mapper;
    private final AiImageLinkService links;

    public AiResultDetails details(String imageId) {
        return tasks.details(imageId);
    }

    public List<AiTask> claim(int size) {
        AiQueueProperties.validateSize(size);
        AiSettings snapshot = settings.read();
        if (!snapshot.enabled()) return List.of();
        links.validateConfiguration();
        long started = System.nanoTime();
        List<AiClaim> claims = tasks.claim(snapshot, size);
        List<AiTask> prepared = new ArrayList<>();
        boolean transientFailure = false;
        for (AiClaim claim : claims) {
            String expected;
            try { expected = mapper.expected(claim.payloadRaw()); }
            catch (IllegalArgumentException e) {
                tasks.preparationFailed(claim, true, "INVALID_EXPECTED");
                log.warn("AI preparation failed: imageId={}, code=INVALID_EXPECTED", claim.imageId());
                continue;
            }
            try {
                var url = links.create(claim.imageId(), claim.leaseExpiresAt());
                if (tasks.savePrepared(claim, expected)) prepared.add(new AiTask(claim.imageId(), claim.claimId(), url,
                        expected, "SINGLE_DECK", claim.leaseExpiresAt()));
            } catch (AiQueueException e) {
                if (e.code().equals("DELIVERY_NOT_CONFIGURED")) {
                    for (AiClaim own : claims) tasks.preparationFailed(own, false, e.code());
                    throw e;
                }
                tasks.preparationFailed(claim, false, e.code());
                log.warn("AI preparation failed: imageId={}, code={}", claim.imageId(), e.code());
                transientFailure = true;
            }
        }
        var now = tasks.databaseNow();
        prepared.removeIf(task -> !task.leaseExpiresAt().isAfter(now));
        if (prepared.isEmpty() && transientFailure) {
            throw new AiQueueException(HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_UNAVAILABLE", "No image could be prepared; retry later");
        }
        log.info("AI claim completed: requested={}, issued={}, durationMs={}", size, prepared.size(), (System.nanoTime()-started)/1_000_000);
        return List.copyOf(prepared);
    }
    public void complete(String imageId, AiResult result) { tasks.complete(imageId, result); }
}
