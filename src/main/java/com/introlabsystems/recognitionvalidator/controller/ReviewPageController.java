package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import com.introlabsystems.recognitionvalidator.dto.request.ReviewFilterRequest;
import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;
import com.introlabsystems.recognitionvalidator.ai.model.AiVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.util.MultiValueMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ReviewPageController {

    private final ValidatorProperties properties;
    private final ObjectMapper mapper;

    public ReviewPageController(ValidatorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @GetMapping("/review")
    String review(Model model, java.security.Principal principal,
                  @Valid @ModelAttribute ReviewFilterRequest filters,
                  @RequestParam MultiValueMap<String, String> query) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (query.containsKey("queue")) {
            var supported = java.util.Set.of("queue", "createdFrom", "createdTo", "tokenId", "sessionId", "gameCode",
                    "notification", "hasUserHand", "aiResult", "aiVerdict", "confidenceFrom", "confidenceTo");
            boolean failedDetails = filters.aiResult() == AiResultState.FAILED
                    && (filters.aiVerdict() != null && filters.aiVerdict() != AiVerdict.ALL
                    || filters.confidenceFrom() != null || filters.confidenceTo() != null);
            if (!"1".equals(query.getFirst("queue"))
                    || query.entrySet().stream().anyMatch(entry -> !supported.contains(entry.getKey()) || entry.getValue().size() != 1)
                    || filters.gameCode() != null && !properties.games().contains(filters.gameCode())
                    || filters.tokenId() != null && (filters.tokenId() < 0 || filters.tokenId() > 9007199254740991L)
                    || filters.sessionId() != null && filters.sessionId().length() > 128 || failedDetails) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operator queue link");
            }
            model.addAttribute("sharedFilters", mapper.writeValueAsString(filters));
        }
        model.addAttribute("operatorName", principal.getName());
        model.addAttribute("games", properties.games());
        model.addAttribute(
                "countRemainingScreenshots",
                properties.countRemainingScreenshots()
        );
        return "review";
    }
}
