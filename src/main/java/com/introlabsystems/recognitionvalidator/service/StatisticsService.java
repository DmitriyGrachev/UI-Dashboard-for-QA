package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.statistics.OperatorStatistics;

import java.util.UUID;

public interface StatisticsService {

    OperatorStatistics forOperator(UUID operatorId);
}
