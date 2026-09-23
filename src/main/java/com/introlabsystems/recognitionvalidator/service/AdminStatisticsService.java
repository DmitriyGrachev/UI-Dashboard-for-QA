package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.value.AdminStatisticsPage;
import com.introlabsystems.recognitionvalidator.model.value.AdminOverviewStatistics;

public interface AdminStatisticsService {

    AdminStatisticsPage page(int requestedPage);

    AdminOverviewStatistics overview(int days);
}
