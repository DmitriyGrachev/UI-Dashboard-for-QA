package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class AdminScreenshotPageController {

    private final ValidatorProperties properties;

    @GetMapping("/admin/screenshots")
    String screenshots(Model model, Principal principal) {
        model.addAttribute("games", properties.games());
        model.addAttribute("adminName", principal.getName());
        return "admin-screenshots";
    }
}
