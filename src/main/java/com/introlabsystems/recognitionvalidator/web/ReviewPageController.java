package com.introlabsystems.recognitionvalidator.web;

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
    String review(Model model) {
        model.addAttribute("games", properties.games());
        model.addAttribute(
                "countRemainingScreenshots",
                properties.countRemainingScreenshots()
        );
        return "review";
    }
}
