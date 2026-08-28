package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotFilters;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotDetails;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotPage;

public interface AdminScreenshotService {

    AdminScreenshotPage search(AdminScreenshotFilters filters);

    AdminScreenshotDetails details(String imageId);
}
