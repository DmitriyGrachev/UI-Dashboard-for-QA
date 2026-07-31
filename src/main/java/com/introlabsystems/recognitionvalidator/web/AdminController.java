package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.auth.AdminUserService;
import com.introlabsystems.recognitionvalidator.auth.AdminUserException;
import com.introlabsystems.recognitionvalidator.statistics.AdminStatisticsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.UUID;

@Controller
public class AdminController {

    private final AdminUserService users;
    private final AdminStatisticsService statistics;

    public AdminController(
            AdminUserService users,
            AdminStatisticsService statistics
    ) {
        this.users = users;
        this.statistics = statistics;
    }

    @GetMapping("/admin")
    String admin(Model model, Principal principal) {
        model.addAttribute("operators", statistics.lastSevenDays());
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

    @ExceptionHandler(AdminUserException.class)
    String adminUserError(AdminUserException exception) {
        return "redirect:/admin?error=" + exception.code();
    }
}
