package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j @Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AiTaskController.class, AiSettingsController.class, AiOperationsController.class})
public class AiExceptionHandler {
    @ExceptionHandler(AiQueueException.class)
    ResponseEntity<Error> queue(AiQueueException e) {
        if (e.status().is5xxServerError()) {
            log.error("AI request failed: code={}", e.code(), e);
        } else {
            log.warn("AI request rejected: code={}", e.code());
        }
        return response(e.status(), e.code(), e.getMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalid(Exception e) {
        log.debug("AI request is invalid: type={}", e.getClass().getSimpleName());
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Check JSON field types, required values and filter ranges");
    }
    @ExceptionHandler(BadSqlGrammarException.class)
    ResponseEntity<Error> schema(BadSqlGrammarException e) {
        log.error("AI database schema or query error", e);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_SCHEMA_ERROR",
                "AI queue could not read the database. Ask an administrator to check the database migration and server logs.");
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Error> database(Exception e, HttpServletRequest request) {
        log.error("AI database request failed: type={}", e.getClass().getSimpleName(), e);
        boolean resultSubmission = request.getRequestURI().endsWith("/result");
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", resultSubmission
                ? "Retry later with the same result claimId"
                : "AI queue database is temporarily unavailable. Retry later.");
    }
    private ResponseEntity<Error> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
    }
    public record Error(String code, String message) {}
}
