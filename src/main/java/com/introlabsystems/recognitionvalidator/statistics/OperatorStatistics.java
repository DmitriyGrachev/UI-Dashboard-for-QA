package com.introlabsystems.recognitionvalidator.statistics;

import java.math.BigDecimal;

public record OperatorStatistics(
        long today,
        long retainedTotal,
        long periodTotal,
        long accepted,
        long rejected,
        BigDecimal acceptedPercent,
        BigDecimal rejectedPercent
) {
}
