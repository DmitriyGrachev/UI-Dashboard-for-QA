package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.auth.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.statistics.OperatorStatistics;
import com.introlabsystems.recognitionvalidator.statistics.StatisticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Controller
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final Clock clock;

    public StatisticsController(StatisticsService statisticsService, Clock clock) {
        this.statisticsService = statisticsService;
        this.clock = clock;
    }

    @GetMapping("/statistics")
    String page(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Model model
    ) {
        DateRange range = dateRange(from, to);
        model.addAttribute("statistics", statisticsService.forOperator(
                principal.id(),
                range.fromInstant(),
                range.toExclusiveInstant()
        ));
        model.addAttribute("from", range.from());
        model.addAttribute("to", range.to());
        model.addAttribute("operatorName", principal.getUsername());
        return "statistics";
    }

    @GetMapping("/api/statistics/me")
    @ResponseBody
    OperatorStatistics currentOperator(
            @AuthenticationPrincipal OperatorPrincipal principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = dateRange(from, to);
        return statisticsService.forOperator(
                principal.id(),
                range.fromInstant(),
                range.toExclusiveInstant()
        );
    }

    private DateRange dateRange(LocalDate from, LocalDate to) {
        LocalDate todayUtc = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate actualFrom = from == null ? todayUtc.minusDays(6) : from;
        LocalDate actualTo = to == null ? todayUtc : to;
        if (actualTo.isBefore(actualFrom)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Дата окончания не может быть раньше даты начала"
            );
        }
        return new DateRange(actualFrom, actualTo);
    }

    private record DateRange(LocalDate from, LocalDate to) {

        Instant fromInstant() {
            return from.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant toExclusiveInstant() {
            return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }
}
