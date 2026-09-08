package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.AdminScreenshotRepository;
import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotDetails;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotFilters;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotPage;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotSummary;
import com.introlabsystems.recognitionvalidator.service.AdminScreenshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminScreenshotServiceImpl implements AdminScreenshotService {

    private final AdminScreenshotRepository screenshots;
    private final B2StorageProperties b2Properties;
    private final Clock clock;

    @Override
    public AdminScreenshotPage search(AdminScreenshotFilters filters) {
        long started = System.nanoTime();
        AdminScreenshotPage page = screenshots.search(
                filters,
                clock.instant().minus(b2Properties.metadataRetention())
        );
        log.debug(
                "Admin screenshot search completed: limit={}, returned={}, hasNext={}, durationMs={}",
                filters.limit(),
                page.items().size(),
                page.nextId() != null,
                (System.nanoTime() - started) / 1_000_000
        );
        return page;
    }

    @Override
    public AdminScreenshotSummary summary(AdminScreenshotFilters filters) {
        long started = System.nanoTime();
        AdminScreenshotSummary summary = screenshots.summary(
                filters,
                clock.instant().minus(b2Properties.metadataRetention())
        );
        log.debug(
                "Admin screenshot summary completed: total={}, durationMs={}",
                summary.totalCount(),
                (System.nanoTime() - started) / 1_000_000
        );
        return summary;
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
