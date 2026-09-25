package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ReviewPageController {

    private final ValidatorProperties properties;

    public ReviewPageController(ValidatorProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/review")
    String review(Model model, java.security.Principal principal) {
        model.addAttribute("operatorName", principal.getName());
        model.addAttribute("games", properties.games());
        model.addAttribute(
                "countRemainingScreenshots",
                properties.countRemainingScreenshots()
        );
        return "review";
    }
}
