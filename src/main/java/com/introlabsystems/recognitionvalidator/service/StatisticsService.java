package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.value.OperatorStatistics;

import java.util.UUID;

public interface StatisticsService {

    OperatorStatistics forOperator(UUID operatorId);
}
