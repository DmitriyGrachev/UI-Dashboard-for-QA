package com.introlabsystems.recognitionvalidator.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.dao.jdbc.RejectedScreenshotExportRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.RejectedScreenshotExportRepository.ExportCandidate;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import com.introlabsystems.recognitionvalidator.service.RejectedScreenshotExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.introlabsystems.recognitionvalidator.service.Csv.cell;

@Service
@RequiredArgsConstructor
@Slf4j
public class RejectedScreenshotExportServiceImpl implements RejectedScreenshotExportService {

    private final RejectedScreenshotExportRepository exports;
    private final ImageStorageService storage;
    private final Clock clock;
    private final RejectedScreenshotExportCompletion completion;
    private final ObjectMapper mapper;
    private final Lock exportLock = new ReentrantLock();

    @Override
    public int writeZip(
            Instant processedFrom,
            Instant processedTo,
            boolean includePreviouslyDownloaded,
            OutputStream output,
            String administrator,
            String sessionId,
            boolean aiMismatch
    ) throws IOException {
        exportLock.lock();
        UUID exportId = UUID.randomUUID();
        long started = System.nanoTime();
        // ponytail: IDs and ZIP's central directory still grow with entry count; use split archives if that becomes limiting.
        List<String> writtenIds = new ArrayList<>();
        int skipped = 0;
        Path manifest = null;
        Path snapshot = null;
        try {
            snapshot = Files.createTempFile("rv-export-", ".json");
            try (var candidates = mapper.writerFor(ExportCandidate.class)
                    .without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE).writeValues(snapshot.toFile())) {
                exports.forEachCandidate(processedFrom, processedTo, includePreviouslyDownloaded, sessionId, aiMismatch,
                        candidate -> {
                            try { candidates.write(candidate); }
                            catch (IOException exception) { throw new UncheckedIOException(exception); }
                        });
            }
            // The selection is fixed and its database connection is released before B2 delivery starts.
            try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(output) {
                @Override public void close() throws IOException { flush(); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }
            })) {
                manifest = Files.createTempFile("rv-export-", ".csv");
                try (var csv = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8);
                     var candidates = mapper.readerFor(ExportCandidate.class).<ExportCandidate>readValues(snapshot.toFile())) {
                    csv.write("\uFEFFfile_name,image_id,session_id,ai_verdict,ai_confidence,ai_message,operator_decision\r\n");
                    while (candidates.hasNextValue()) {
                        var candidate = candidates.nextValue();
                        try {
                            var content = storage.open(candidate.imageId());
                            boolean entryOpened = false;
                            try (InputStream input = content.resource().getInputStream()) {
                                zip.putNextEntry(new ZipEntry(content.fileName()));
                                entryOpened = true;
                                input.transferTo(zip);
                            } finally {
                                if (entryOpened) zip.closeEntry();
                            }
                            writtenIds.add(candidate.imageId());
                            csv.write(String.join(",", cell(content.fileName()), cell(candidate.imageId()),
                                    cell(candidate.sessionId()), cell(candidate.aiVerdict()), cell(candidate.aiConfidence()),
                                    cell(candidate.aiMessage()), cell(candidate.operatorDecision())) + "\r\n");
                        } catch (ImageNotFoundException exception) {
                            // The storage service marks the stale database row unavailable.
                            skipped++;
                            log.debug("Rejected screenshot skipped during export: exportId={}, imageId={}",
                                    exportId, candidate.imageId());
                        }
                    }
                }
                zip.putNextEntry(new ZipEntry("results.csv"));
                Files.copy(manifest, zip);
                zip.closeEntry();
                zip.finish();
                zip.flush();
            }
            if (!aiMismatch && !writtenIds.isEmpty()) {
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
            for (Path file : new Path[]{manifest, snapshot}) {
                if (file == null) continue;
                try { Files.deleteIfExists(file); }
                catch (IOException exception) { log.warn("Could not remove export temporary file: exportId={}", exportId, exception); }
            }
            exportLock.unlock();
        }
    }
}
