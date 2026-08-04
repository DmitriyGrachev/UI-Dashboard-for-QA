package com.introlabsystems.recognitionvalidator.model.value;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

public record OperatorDailyStatisticsId(
        UUID operatorId,
        LocalDate statisticsDate
) implements Serializable {
}
