package com.introlabsystems.recognitionvalidator.review;

import com.introlabsystems.recognitionvalidator.image.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.image.ImageStorageService;
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
public class RejectedScreenshotExportService {

    private final RejectedScreenshotExportRepository exports;
    private final ImageStorageService storage;
    private final Clock clock;
    private final Lock exportLock = new ReentrantLock();

    public RejectedScreenshotExportService(
            RejectedScreenshotExportRepository exports,
            ImageStorageService storage,
            Clock clock
    ) {
        this.exports = exports;
        this.storage = storage;
        this.clock = clock;
    }

    public int writeZip(
            Instant createdFrom,
            Instant createdTo,
            boolean includePreviouslyDownloaded,
            OutputStream output
    ) throws IOException {
        exportLock.lock();
        try {
            List<String> writtenIds = new ArrayList<>();
            ZipOutputStream zip = new ZipOutputStream(output);
            for (var candidate : exports.findCandidates(
                    createdFrom,
                    createdTo,
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
