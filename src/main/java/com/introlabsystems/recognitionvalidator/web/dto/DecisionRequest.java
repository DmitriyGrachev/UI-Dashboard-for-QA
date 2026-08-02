package com.introlabsystems.recognitionvalidator.web.dto;

import com.introlabsystems.recognitionvalidator.review.Decision;
import com.introlabsystems.recognitionvalidator.review.ReviewFilters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record DecisionRequest(
        @NotNull Decision decision,
        @Valid ReviewFilterRequest filters
) {
    public ReviewFilters toFilters() {
        return filters == null ? ReviewFilters.none() : filters.toFilters();
    }
}
