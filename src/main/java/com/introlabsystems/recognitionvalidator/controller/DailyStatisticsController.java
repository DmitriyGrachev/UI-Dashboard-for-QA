package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.config.OpenApiConfig;
import com.introlabsystems.recognitionvalidator.service.DailyStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/integration/statistics/daily")
@RequiredArgsConstructor
@Slf4j
public class DailyStatisticsController {
    private final DailyStatisticsService statistics;

    @GetMapping
    @Operation(summary = "Read operator and AI statistics for one UTC day",
            description = "Date defaults to today in UTC. Confidence counts cover retained results; check completeCoverage.")
    @SecurityRequirement(name = OpenApiConfig.STATISTICS_API_KEY)
    public ResponseEntity<DailyStatisticsService.Daily> read(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(statistics.read(date));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail invalidDate() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Use a valid date in YYYY-MM-DD format");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception error) {
        log.error("Daily statistics query failed", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Statistics are temporarily unavailable"));
    }
}
