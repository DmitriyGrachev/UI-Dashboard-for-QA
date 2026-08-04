package com.introlabsystems.recognitionvalidator.dto.request;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
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
