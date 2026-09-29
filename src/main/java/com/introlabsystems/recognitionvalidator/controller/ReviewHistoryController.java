package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewHistoryRepository;
import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ReviewHistoryController {
    private final ReviewHistoryRepository history;

    @GetMapping("/history")
    String page(@AuthenticationPrincipal OperatorPrincipal operator, Model model,
                @RequestParam(required = false) Instant beforeAt,
                @RequestParam(required = false) String beforeId, HttpServletResponse response) {
        if ((beforeAt == null) != (beforeId == null) || beforeId != null && !beforeId.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History cursor requires a timestamp and image ID");
        }
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("operatorName", operator.getUsername());
        model.addAttribute("beforeAt", beforeAt);
        model.addAttribute("beforeId", beforeId);
        model.addAttribute("history", List.of());
        try {
            var entries = history.recent(operator.id(), beforeAt, beforeId);
            boolean more = entries.size() > ReviewHistoryRepository.PAGE_SIZE;
            var visible = more ? entries.subList(0, ReviewHistoryRepository.PAGE_SIZE) : entries;
            model.addAttribute("history", visible);
            model.addAttribute("next", more ? visible.getLast() : null);
        } catch (DataAccessException | TransactionException exception) {
            log.warn("Operator history unavailable", exception);
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            model.addAttribute("historyError", true);
        }
        return "review-history";
    }
}
