package com.introlabsystems.recognitionvalidator.ai.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AiExceptionHandlerLoggingTest {

    @Test
    void databaseFailureIsLoggedAsErrorWithCause(CapturedOutput output) {
        new AiExceptionHandler().database(new QueryTimeoutException("statement timeout"),
                new MockHttpServletRequest("GET", "/admin/api/ai-queue/settings"));

        assertThat(output)
                .contains("ERROR")
                .contains("AI database request failed")
                .contains("statement timeout");
    }
}
