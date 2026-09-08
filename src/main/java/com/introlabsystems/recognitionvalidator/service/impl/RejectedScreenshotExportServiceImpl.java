package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.dao.jdbc.RejectedScreenshotExportRepository;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import com.introlabsystems.recognitionvalidator.service.RejectedScreenshotExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        UUID exportId = UUID.randomUUID();
        long started = System.nanoTime();
        List<String> writtenIds = new ArrayList<>();
        int skipped = 0;
        try {
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
                } catch (ImageNotFoundException exception) {
                    // The storage service marks the stale database row unavailable.
                    skipped++;
                    log.debug(
                            "Rejected screenshot skipped during export: exportId={}, imageId={}",
                            exportId,
                            candidate.imageId()
                    );
                }
            }
            zip.finish();
            zip.flush();
            if (!writtenIds.isEmpty()) {
                completion.complete(exportId, administrator, writtenIds, clock.instant());
            }
            log.info(
                    "Rejected screenshot export completed: exportId={}, written={}, skipped={}, durationMs={}",
                    exportId,
                    writtenIds.size(),
                    skipped,
                    (System.nanoTime() - started) / 1_000_000
            );
            return writtenIds.size();
        } catch (IOException | RuntimeException exception) {
            log.error(
                    "Rejected screenshot export failed: exportId={}, written={}, skipped={}, durationMs={}",
                    exportId,
                    writtenIds.size(),
                    skipped,
                    (System.nanoTime() - started) / 1_000_000,
                    exception
            );
            throw exception;
        } finally {
            exportLock.unlock();
        }
    }
}
