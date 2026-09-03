package com.introlabsystems.recognitionvalidator.ai.controller;

import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j @Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AiTaskController.class, AiSettingsController.class})
public class AiExceptionHandler {
    @ExceptionHandler(AiQueueException.class)
    ResponseEntity<Error> queue(AiQueueException e) {
        log.warn("AI request rejected: code={}", e.code());
        return response(e.status(), e.code(), e.getMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalid(Exception e) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Check JSON field types, required values and filter ranges");
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Error> database(Exception e) {
        log.warn("AI database request failed: type={}", e.getClass().getSimpleName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Retry later with the same result claimId");
    }
    private ResponseEntity<Error> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
    }
    public record Error(String code, String message) {}
}
