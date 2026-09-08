package com.introlabsystems.recognitionvalidator.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import com.introlabsystems.recognitionvalidator.ai.mapper.AiCardPayloadMapper;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.ai.security.AiLocalImageUrlSigner;
import com.introlabsystems.recognitionvalidator.ai.service.AiImageLinkService;
import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiDeliveryFailureTest {
    final Instant now = Instant.parse("2026-09-03T10:00:00Z");
    final AiSettingsRepository settings = mock(AiSettingsRepository.class);
    final AiTaskRepository tasks = mock(AiTaskRepository.class);
    final AiImageLinkService links = mock(AiImageLinkService.class);
    final AiQueueService service = new AiQueueService(settings, tasks, new AiCardPayloadMapper(), links);

    AiClaim claim(int id, String payload) {
        return new AiClaim("%064x".formatted(id), UUID.randomUUID(), now.plusSeconds(120), payload);
    }
    void setup(AiClaim... claims) {
        var snapshot = new AiSettings(0, true, List.of(AiSettingsRepositoryTest.rule(10, null)));
        when(settings.read()).thenReturn(snapshot);
        when(tasks.claim(snapshot, claims.length)).thenReturn(List.of(claims));
        when(tasks.databaseNow()).thenReturn(now);
        when(tasks.savePrepared(any(), any())).thenReturn(true);
    }

    @Test void signaturesExpireAndCannotAuthorizeAnotherImage() {
        String key = "01234567890123456789012345678901";
        var signer = new AiLocalImageUrlSigner(key, Clock.fixed(now, ZoneOffset.UTC));
        String first = "%064x".formatted(1), second = "%064x".formatted(2);
        long expires = now.plusSeconds(150).getEpochSecond();
        String signature = signer.sign(first, expires);
        assertThat(signer.verify(first, Long.toString(expires), signature)).isTrue();
        assertThat(signer.verify(second, Long.toString(expires), signature)).isFalse();
        assertThat(signer.verify(first, Long.toString(expires + 1), signature)).isFalse();
        assertThat(new AiLocalImageUrlSigner(key, Clock.fixed(Instant.ofEpochSecond(expires), ZoneOffset.UTC))
                .verify(first, Long.toString(expires), signature)).isFalse();
    }

    @Test void oneBadPayloadDoesNotHideAnotherPreparedItem() {
        var bad = claim(1, "d_King");
        var good = claim(2, "d_Six_u_Seven_King_bSbH");
        setup(bad, good);
        when(links.create(good.imageId(), good.leaseExpiresAt())).thenReturn(URI.create("https://example.com/image.png"));
        assertThat(service.claim(2)).extracting(AiTask::imageId).containsExactly(good.imageId());
        verify(tasks).preparationFailed(bad, true, "INVALID_EXPECTED");
        verify(links, never()).create(eq(bad.imageId()), any());
    }

    @Test void temporarySourceFailureIsNotAnAiMismatch() {
        var item = claim(1, "u_Seven_King_bS");
        setup(item);
        when(links.create(any(), any())).thenThrow(new AiQueueException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(() -> service.claim(1)).isInstanceOf(AiQueueException.class).hasMessageContaining("DELIVERY_UNAVAILABLE");
        verify(tasks).preparationFailed(item, false, "IMAGE_UNAVAILABLE");
        verify(tasks, never()).complete(any(), any());
    }

    @Test void lateConfigurationFailureReleasesAllUnreturnedClaims() {
        var first = claim(1, "u_Seven_King_bS");
        var second = claim(2, "u_Seven_King_bS");
        setup(first, second);
        when(links.create(first.imageId(), first.leaseExpiresAt())).thenReturn(URI.create("https://example.com/image.png"));
        when(links.create(second.imageId(), second.leaseExpiresAt())).thenThrow(new AiQueueException(HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_NOT_CONFIGURED", "Configure"));
        assertThatThrownBy(() -> service.claim(2)).isInstanceOf(AiQueueException.class);
        verify(tasks).preparationFailed(first, false, "DELIVERY_NOT_CONFIGURED");
        verify(tasks).preparationFailed(second, false, "DELIVERY_NOT_CONFIGURED");
    }

    @Test void successfulClaimsUseDebugToAvoidHighVolumeInfoLogs() {
        var item = claim(1, "u_Seven_King_bS");
        setup(item);
        when(links.create(item.imageId(), item.leaseExpiresAt()))
                .thenReturn(URI.create("https://example.com/image.png"));
        Logger logger = (Logger) LoggerFactory.getLogger(AiQueueService.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.claim(1);

            assertThat(appender.list)
                    .filteredOn(event -> event.getFormattedMessage().contains("AI claim completed"))
                    .singleElement()
                    .extracting(ILoggingEvent::getLevel)
                    .isEqualTo(Level.DEBUG);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }
}
