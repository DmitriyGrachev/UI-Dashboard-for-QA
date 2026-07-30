package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.image.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.review.DecisionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed"
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableRequest(HttpMessageNotReadableException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body is invalid"
        );
    }

    @ExceptionHandler(ImageNotFoundException.class)
    ProblemDetail missingImage(ImageNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
    }

    @ExceptionHandler(DecisionConflictException.class)
    ProblemDetail decisionConflict(DecisionConflictException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
    }
}
