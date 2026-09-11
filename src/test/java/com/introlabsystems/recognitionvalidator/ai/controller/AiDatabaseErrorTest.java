package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.repository.AiOperationsRepository;
import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.SQLException;
import java.time.Clock;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiDatabaseErrorTest {
    @Test
    void resultTimeoutKeepsTheIdempotentRetryInstruction() throws Exception {
        var queue = mock(AiQueueService.class);
        doThrow(new QueryTimeoutException("timeout")).when(queue).complete(any(), any());

        MockMvcBuilders.standaloneSetup(new AiTaskController(queue, new ObjectMapper()))
                .setControllerAdvice(new AiExceptionHandler()).build()
                .perform(post("/api/integration/ai/tasks/" + "a".repeat(64) + "/result")
                        .contentType("application/json")
                        .content("""
                                {"claimId":"314fd2b3-0e1a-46e8-a974-a5a99d40a01b","valid":true,"verdict":"MATCH"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Retry later with the same result claimId"));
    }

    @Test
    void schemaFailureExplainsRequiredRepairWithoutExposingSql() throws Exception {
        var repository = mock(AiOperationsRepository.class);
        when(repository.snapshot(any())).thenThrow(new BadSqlGrammarException(
                "read", "SELECT private_column FROM private_table", new SQLException("missing column")));

        MockMvcBuilders.standaloneSetup(new AiOperationsController(repository, Clock.systemUTC()))
                .setControllerAdvice(new AiExceptionHandler()).build()
                .perform(get("/admin/api/ai-queue/operations"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_SCHEMA_ERROR"))
                .andExpect(jsonPath("$.message", containsString("migration")))
                .andExpect(content().string(not(containsString("claimId"))))
                .andExpect(content().string(not(containsString("private_"))));
    }

    @Test
    void adminTimeoutDoesNotAskForAResultClaimId() throws Exception {
        var repository = mock(AiOperationsRepository.class);
        when(repository.snapshot(any())).thenThrow(new QueryTimeoutException("timeout"));

        MockMvcBuilders.standaloneSetup(new AiOperationsController(repository, Clock.systemUTC()))
                .setControllerAdvice(new AiExceptionHandler()).build()
                .perform(get("/admin/api/ai-queue/operations"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("claimId"))));
    }
}
