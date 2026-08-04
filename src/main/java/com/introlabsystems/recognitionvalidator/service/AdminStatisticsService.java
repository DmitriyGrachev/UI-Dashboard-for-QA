package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.statistics.AdminStatisticsPage;

public interface AdminStatisticsService {

    AdminStatisticsPage page(int requestedPage);
}
