package com.introlabsystems.recognitionvalidator.web.dto;

import com.introlabsystems.recognitionvalidator.review.ReviewFilters;
import jakarta.validation.Valid;

public record ReviewClaimRequest(
        @Valid ReviewFilterRequest filters,
        boolean replaceCurrent,
        boolean includeRemaining
) {

    public ReviewFilters toFilters() {
        return filters == null ? ReviewFilters.none() : filters.toFilters();
    }
}
