package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.exception.DecisionConflictException;
import com.introlabsystems.recognitionvalidator.exception.AdminUserException;
import com.introlabsystems.recognitionvalidator.exception.ImageStorageUnavailableException;
import com.introlabsystems.recognitionvalidator.service.AdminStatisticsService;
import com.introlabsystems.recognitionvalidator.service.AdminUserService;
import com.introlabsystems.recognitionvalidator.service.RejectedScreenshotExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerLoggingTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void storageFailureIsLoggedAsErrorWithCause(CapturedOutput output) {
        handler.imageStorageUnavailable(new ImageStorageUnavailableException(
                new IllegalStateException("B2 unavailable")
        ));

        assertThat(output)
                .contains("ERROR")
                .contains("Image storage request failed")
                .contains("B2 unavailable");
    }

    @Test
    void staleOperatorDecisionIsLoggedAsWarning(CapturedOutput output) {
        String imageId = "b".repeat(64);

        handler.decisionConflict(new DecisionConflictException(imageId));

        assertThat(output)
                .contains("WARN")
                .contains("Review decision conflict")
                .contains(imageId);
    }

    @Test
    void rejectedAdminOperationLogsSafeErrorCode(CapturedOutput output) {
        AdminController controller = new AdminController(
                mock(AdminUserService.class),
                mock(AdminStatisticsService.class),
                mock(RejectedScreenshotExportService.class)
        );

        controller.adminUserError(new AdminUserException("username", "private input details"));

        assertThat(output)
                .contains("WARN")
                .contains("Admin operator operation rejected: code=username")
                .doesNotContain("private input details");
    }
}
