package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.exception.CloudImageNotAvailableException;
import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.exception.ImageStorageUnavailableException;
import com.introlabsystems.recognitionvalidator.exception.DecisionConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException exception) {
        log.debug("Request validation failed: type={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed"
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableRequest(HttpMessageNotReadableException exception) {
        log.debug("Request body is unreadable: type={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body is invalid"
        );
    }

    @ExceptionHandler(ImageNotFoundException.class)
    ProblemDetail missingImage(ImageNotFoundException exception) {
        log.debug("Image request could not be served: {}", exception.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
    }

    @ExceptionHandler(ImageStorageUnavailableException.class)
    ProblemDetail imageStorageUnavailable(ImageStorageUnavailableException exception) {
        log.error("Image storage request failed", exception);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Image storage is temporarily unavailable"
        );
    }

    @ExceptionHandler(CloudImageNotAvailableException.class)
    ProblemDetail cloudImageNotAvailable(CloudImageNotAvailableException exception) {
        log.warn("Cloud image link unavailable: {}", exception.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
    }

    @ExceptionHandler(DecisionConflictException.class)
    ProblemDetail decisionConflict(DecisionConflictException exception) {
        log.warn("Review decision conflict: {}", exception.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
    }
}
