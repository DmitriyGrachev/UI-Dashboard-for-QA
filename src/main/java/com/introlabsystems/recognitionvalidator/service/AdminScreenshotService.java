package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotFilters;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotDetails;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotPage;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotSummary;

public interface AdminScreenshotService {

    AdminScreenshotPage search(AdminScreenshotFilters filters);

    AdminScreenshotSummary summary(AdminScreenshotFilters filters);

    AdminScreenshotDetails details(String imageId);
}
