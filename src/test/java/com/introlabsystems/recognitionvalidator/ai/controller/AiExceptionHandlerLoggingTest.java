package com.introlabsystems.recognitionvalidator.ai.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AiExceptionHandlerLoggingTest {

    @Test
    void databaseFailureIsLoggedAsErrorWithCause(CapturedOutput output) {
        new AiExceptionHandler().database(new QueryTimeoutException("statement timeout"));

        assertThat(output)
                .contains("ERROR")
                .contains("AI database request failed")
                .contains("statement timeout");
    }
}
