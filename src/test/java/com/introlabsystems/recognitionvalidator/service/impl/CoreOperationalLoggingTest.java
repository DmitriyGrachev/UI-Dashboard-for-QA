package com.introlabsystems.recognitionvalidator.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.introlabsystems.recognitionvalidator.ai.dto.AiResult;
import com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.ai.service.AiImageLinkService;
import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.AdminScreenshotRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.RejectedScreenshotExportRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.dao.jpa.AppUserRepository;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueResult;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotFilters;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotPage;
import com.introlabsystems.recognitionvalidator.security.UserSessionService;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CoreOperationalLoggingTest {

    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");

    @Test
    void reviewClaimLogsOutcomeWithoutFilterValues(CapturedOutput output) {
        ReviewClaimRepository repository = mock(ReviewClaimRepository.class);
        UUID operatorId = UUID.randomUUID();
        ReviewFilters filters = new ReviewFilters(null, null, null, "private-session", null, null, null);
        when(repository.claim(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(new ReviewQueueResult(Optional.empty(), null));
        ReviewQueueServiceImpl service = new ReviewQueueServiceImpl(
                repository,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        withDebugLogging(ReviewQueueServiceImpl.class,
                () -> service.claim(operatorId, filters, true, false));

        assertThat(output)
                .contains("Review queue claim completed")
                .contains("operatorId=" + operatorId)
                .contains("assigned=false")
                .doesNotContain("private-session");
    }

    @Test
    void operatorCreationLogsGeneratedIdWithoutCredentials(CapturedOutput output) {
        AppUserRepository users = mock(AppUserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("plain-password")).thenReturn("hashed-password");
        AdminUserServiceImpl service = new AdminUserServiceImpl(
                users,
                passwords,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(JdbcTemplate.class),
                mock(UserSessionService.class)
        );

        service.createOperator("operator", "plain-password");

        assertThat(output)
                .contains("Operator created: operatorId=")
                .doesNotContain("plain-password")
                .doesNotContain("hashed-password");
    }

    @Test
    void rejectedExportLogsUnexpectedFailureWithOperationContext(CapturedOutput output) {
        RejectedScreenshotExportRepository exports = mock(RejectedScreenshotExportRepository.class);
        when(exports.findCandidates(null, null, false))
                .thenThrow(new IllegalStateException("database down"));
        RejectedScreenshotExportServiceImpl service = new RejectedScreenshotExportServiceImpl(
                exports,
                mock(ImageStorageService.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(RejectedScreenshotExportCompletion.class)
        );

        assertThatThrownBy(() -> service.writeZip(
                null, null, false, new ByteArrayOutputStream(), "admin"
        )).isInstanceOf(IllegalStateException.class);

        assertThat(output)
                .contains("Rejected screenshot export failed: exportId=")
                .contains("written=0")
                .contains("database down");
    }

    @Test
    void aiCompletionLogsResultWithoutFreeFormMessage(CapturedOutput output) {
        AiTaskRepository tasks = mock(AiTaskRepository.class);
        AiQueueService service = new AiQueueService(
                mock(AiSettingsRepository.class),
                tasks,
                mock(AiImageLinkService.class)
        );
        String imageId = "a".repeat(64);
        AiResult result = new AiResult(
                UUID.randomUUID(), true, "MATCH", 97, 95, "private model details"
        );

        withDebugLogging(AiQueueService.class, () -> service.complete(imageId, result));

        assertThat(output)
                .contains("AI result completed")
                .contains("imageId=" + imageId)
                .contains("verdict=MATCH")
                .doesNotContain("private model details");
    }

    @Test
    void adminScreenshotSearchLogsTimingWithoutFilterValues(CapturedOutput output) {
        AdminScreenshotRepository repository = mock(AdminScreenshotRepository.class);
        B2StorageProperties b2 = mock(B2StorageProperties.class);
        when(b2.metadataRetention()).thenReturn(Duration.ofDays(21));
        AdminScreenshotFilters filters = new AdminScreenshotFilters(
                null, null, null, null, null, "private-session", null, null,
                null, null, null, null, null, null, null, null, 50
        );
        when(repository.search(filters, NOW.minus(Duration.ofDays(21))))
                .thenReturn(new AdminScreenshotPage(List.of(), null, null));
        AdminScreenshotServiceImpl service = new AdminScreenshotServiceImpl(
                repository,
                b2,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        withDebugLogging(AdminScreenshotServiceImpl.class, () -> service.search(filters));

        assertThat(output)
                .contains("Admin screenshot search completed: limit=50, returned=0, hasNext=false")
                .doesNotContain("private-session");
    }

    private static ValidatorProperties properties() {
        return new ValidatorProperties(
                Path.of("screenshots"),
                List.of("bj_single_deck_ags"),
                1_000,
                Duration.ofMinutes(30),
                Duration.ofDays(4),
                5_000,
                0,
                true,
                Duration.ofSeconds(2),
                50_000,
                true
        );
    }

    private static void withDebugLogging(Class<?> type, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            logger.setLevel(previous);
        }
    }
}
