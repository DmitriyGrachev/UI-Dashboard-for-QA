package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.exception.AdminUserException;
import com.introlabsystems.recognitionvalidator.service.AdminStatisticsService;
import com.introlabsystems.recognitionvalidator.service.AdminUserService;
import com.introlabsystems.recognitionvalidator.service.RejectedScreenshotExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class AdminController {

    private final AdminUserService users;
    private final AdminStatisticsService statistics;
    private final RejectedScreenshotExportService rejectedExports;

    @GetMapping("/admin")
    String admin(
            @RequestParam(defaultValue = "0") int page,
            Model model,
            Principal principal
    ) {
        var dashboard = statistics.page(page);
        model.addAttribute("operators", dashboard.operators());
        model.addAttribute("page", dashboard.page());
        model.addAttribute("totalPages", dashboard.totalPages());
        model.addAttribute("totalOperators", dashboard.totalOperators());
        model.addAttribute("hasPrevious", dashboard.hasPrevious());
        model.addAttribute("hasNext", dashboard.hasNext());
        model.addAttribute("adminName", principal.getName());
        return "admin";
    }

    @PostMapping("/admin/operators")
    String createOperator(
            @RequestParam String username,
            @RequestParam String password
    ) {
        users.createOperator(username, password);
        return "redirect:/admin?created";
    }

    @PostMapping("/admin/operators/{operatorId}/deactivate")
    String deactivateOperator(@PathVariable UUID operatorId) {
        users.deactivateOperator(operatorId);
        return "redirect:/admin?deactivated";
    }

    @PostMapping("/admin/operators/{operatorId}/restore")
    String restoreOperator(@PathVariable UUID operatorId) {
        users.restoreOperator(operatorId);
        return "redirect:/admin?restored";
    }

    @PostMapping("/admin/operators/{operatorId}/password")
    String changePassword(
            @PathVariable UUID operatorId,
            @RequestParam String password
    ) {
        users.changePassword(operatorId, password);
        return "redirect:/admin?passwordChanged";
    }

    @PostMapping("/admin/rejected-screenshots.zip")
    void downloadRejectedScreenshots(
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
            LocalDateTime processedFrom,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
            LocalDateTime processedTo,
            @RequestParam(defaultValue = "false") boolean includePreviouslyDownloaded,
            HttpServletResponse response
    ) throws IOException {
        if (processedFrom != null
                && processedTo != null
                && !processedFrom.isBefore(processedTo)) {
            response.sendError(
                    HttpStatus.BAD_REQUEST.value(),
                    "Recognition completed from must be earlier than recognition completed to"
            );
            return;
        }
        response.setContentType("application/zip");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename("rejected-screenshots.zip")
                        .build()
                        .toString()
        );
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        rejectedExports.writeZip(
                processedFrom == null ? null : processedFrom.toInstant(ZoneOffset.UTC),
                processedTo == null ? null : processedTo.toInstant(ZoneOffset.UTC),
                includePreviouslyDownloaded,
                response.getOutputStream()
        );
    }

    @ExceptionHandler(AdminUserException.class)
    String adminUserError(AdminUserException exception) {
        return "redirect:/admin?error=" + exception.code();
    }
}
