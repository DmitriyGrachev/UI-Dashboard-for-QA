package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.dao.jdbc.RejectedScreenshotExportRepository;
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
import java.util.UUID;
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
    private final RejectedScreenshotExportCompletion completion;
    private final Lock exportLock = new ReentrantLock();

    @Override
    public int writeZip(
            Instant processedFrom,
            Instant processedTo,
            boolean includePreviouslyDownloaded,
            OutputStream output,
            String administrator
    ) throws IOException {
        exportLock.lock();
        try {
            UUID exportId = UUID.randomUUID();
            List<String> writtenIds = new ArrayList<>();
            ZipOutputStream zip = new ZipOutputStream(output);
            for (var candidate : exports.findCandidates(
                    processedFrom,
                    processedTo,
                    includePreviouslyDownloaded
            )) {
                try {
                    var content = storage.open(candidate.imageId());
                    boolean entryOpened = false;
                    try (InputStream input = content.resource().getInputStream()) {
                        zip.putNextEntry(new ZipEntry(content.fileName()));
                        entryOpened = true;
                        input.transferTo(zip);
                    } finally {
                        if (entryOpened) {
                            zip.closeEntry();
                        }
                    }
                    writtenIds.add(candidate.imageId());
                } catch (ImageNotFoundException ignored) {
                    // The storage service marks the stale database row unavailable.
                }
            }
            zip.finish();
            zip.flush();
            if (!writtenIds.isEmpty()) {
                completion.complete(exportId, administrator, writtenIds, clock.instant());
            }
            return writtenIds.size();
        } finally {
            exportLock.unlock();
        }
    }
}
