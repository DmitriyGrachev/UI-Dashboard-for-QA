package com.introlabsystems.recognitionvalidator.model.value;

import java.util.List;

public record AdminStatisticsPage(
        List<AdminOperatorStatistics> operators,
        int page,
        int totalPages,
        long totalOperators
) {
    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < totalPages;
    }
}
