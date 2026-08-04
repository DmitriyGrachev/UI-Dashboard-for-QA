package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.model.value.OperatorStatistics;
import com.introlabsystems.recognitionvalidator.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/statistics")
    String page(
            @AuthenticationPrincipal OperatorPrincipal principal,
            Model model
    ) {
        model.addAttribute("statistics", statisticsService.forOperator(principal.id()));
        model.addAttribute("operatorName", principal.getUsername());
        return "statistics";
    }

    @GetMapping("/api/statistics/me")
    @ResponseBody
    OperatorStatistics currentOperator(
            @AuthenticationPrincipal OperatorPrincipal principal
    ) {
        return statisticsService.forOperator(principal.id());
    }
}
