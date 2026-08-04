package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.image.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.review.RejectedScreenshotExportRepository;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import com.introlabsystems.recognitionvalidator.service.RejectedScreenshotExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class RejectedScreenshotExportServiceImpl implements RejectedScreenshotExportService {

    private final RejectedScreenshotExportRepository exports;
    private final ImageStorageService storage;
    private final Clock clock;
    private final Lock exportLock = new ReentrantLock();

    @Override
    public int writeZip(
            Instant processedFrom,
            Instant processedTo,
            boolean includePreviouslyDownloaded,
            OutputStream output
    ) throws IOException {
        exportLock.lock();
        try {
            List<String> writtenIds = new ArrayList<>();
            ZipOutputStream zip = new ZipOutputStream(output);
            for (var candidate : exports.findCandidates(
                    processedFrom,
                    processedTo,
                    includePreviouslyDownloaded
            )) {
                try {
                    var content = storage.open(candidate.imageId());
                    zip.putNextEntry(new ZipEntry(content.fileName()));
                    try (InputStream input = content.resource().getInputStream()) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                    writtenIds.add(candidate.imageId());
                } catch (ImageNotFoundException ignored) {
                    // The storage service marks the stale database row unavailable.
                }
            }
            zip.finish();
            zip.flush();
            exports.markDownloaded(writtenIds, clock.instant());
            return writtenIds.size();
        } finally {
            exportLock.unlock();
        }
    }
}
