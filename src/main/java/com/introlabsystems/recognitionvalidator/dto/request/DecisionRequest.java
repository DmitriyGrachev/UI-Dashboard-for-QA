package com.introlabsystems.recognitionvalidator.dto.request;

import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
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
