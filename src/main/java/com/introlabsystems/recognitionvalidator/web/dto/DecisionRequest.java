package com.introlabsystems.recognitionvalidator.web.dto;

import com.introlabsystems.recognitionvalidator.review.Decision;
import jakarta.validation.constraints.NotNull;

public record DecisionRequest(@NotNull Decision decision) {
}
