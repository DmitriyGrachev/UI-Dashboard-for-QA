package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.AdminScreenshotRepository;
import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotDetails;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotFilters;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotPage;
import com.introlabsystems.recognitionvalidator.service.AdminScreenshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class AdminScreenshotServiceImpl implements AdminScreenshotService {

    private final AdminScreenshotRepository screenshots;
    private final B2StorageProperties b2Properties;
    private final Clock clock;

    @Override
    public AdminScreenshotPage search(AdminScreenshotFilters filters) {
        return screenshots.search(
                filters,
                clock.instant().minus(b2Properties.metadataRetention())
        );
    }

    @Override
    public AdminScreenshotDetails details(String imageId) {
        return screenshots.findById(
                        imageId,
                        clock.instant().minus(b2Properties.metadataRetention())
                )
                .orElseThrow(() -> new ImageNotFoundException(imageId));
    }
}
