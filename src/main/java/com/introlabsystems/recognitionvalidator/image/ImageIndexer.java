package com.introlabsystems.recognitionvalidator.image;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ImageIndexer {

    private static final Logger log = LoggerFactory.getLogger(ImageIndexer.class);

    private final Path imageRoot;
    private final int batchSize;
    private final FilenameParser filenameParser;
    private final ImageAssetBatchWriter batchWriter;
    private final Clock clock;

    @Autowired
    public ImageIndexer(
            ValidatorProperties properties,
            FilenameParser filenameParser,
            ImageAssetBatchWriter batchWriter,
            Clock clock
    ) {
        this(properties.imageRoot(), properties.batchSize(), filenameParser, batchWriter, clock);
    }

    ImageIndexer(
            Path imageRoot,
            int batchSize,
            FilenameParser filenameParser,
            ImageAssetBatchWriter batchWriter,
            Clock clock
    ) {
        this.imageRoot = imageRoot.toAbsolutePath().normalize();
        this.batchSize = batchSize;
        this.filenameParser = filenameParser;
        this.batchWriter = batchWriter;
        this.clock = clock;
    }

    public void scanRoot() {
        Instant scanStartedAt = clock.instant();
        long startedNanos = System.nanoTime();
        boolean compareExisting = batchWriter.hasAvailableAssets();
        List<ImageCandidate> batch = new ArrayList<>(batchSize);
        boolean traversalComplete = false;
        boolean fileError = false;
        long inspected = 0;
        long recognized = 0;
        long written = 0;
        long missing = 0;

        log.info("Scanning image directory {}", imageRoot);

        try {
            Files.createDirectories(imageRoot);
            try (Stream<Path> paths = Files.list(imageRoot)) {
                Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    inspected++;
                    try {
                        java.util.Optional<ImageCandidate> candidate = candidate(path);
                        if (candidate.isPresent()) {
                            batch.add(candidate.get());
                            recognized++;
                        }
                    } catch (RuntimeException exception) {
                        fileError = true;
                        log.warn("Cannot inspect image file {}", path, exception);
                        continue;
                    }
                    if (batch.size() >= batchSize) {
                        BatchResult result = reconcileBatch(batch, scanStartedAt, compareExisting);
                        written += result.written();
                        fileError |= result.fileError();
                        batch.clear();
                    }
                    if (inspected % 100_000 == 0) {
                        log.info(
                                "Image directory scan progress: inspected={}, recognized={}, written={}",
                                inspected,
                                recognized,
                                written
                        );
                    }
                }
            }
            BatchResult result = reconcileBatch(batch, scanStartedAt, compareExisting);
            written += result.written();
            fileError |= result.fileError();
            traversalComplete = true;
        } catch (IOException | SecurityException exception) {
            log.error("Cannot complete image directory scan for {}", imageRoot, exception);
        }

        if (traversalComplete && compareExisting) {
            missing = markMissingFiles();
        }
        if (traversalComplete) {
            log.info(
                    "Image directory scan completed: inspected={}, recognized={}, written={}, "
                            + "missing={}, fileErrors={}, durationMs={}",
                    inspected,
                    recognized,
                    written,
                    missing,
                    fileError,
                    (System.nanoTime() - startedNanos) / 1_000_000
            );
        }
    }

    public void index(Path absolutePath) {
        indexBatch(List.of(absolutePath));
    }

    public void indexBatch(Collection<Path> absolutePaths) {
        if (absolutePaths.isEmpty()) {
            return;
        }
        Instant seenAt = clock.instant();
        List<ImageMetadata> batch = new ArrayList<>(Math.min(batchSize, absolutePaths.size()));
        for (Path absolutePath : absolutePaths) {
            try {
                if (Files.isRegularFile(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
                    java.util.Optional<ImageCandidate> candidate = candidate(absolutePath);
                    if (candidate.isPresent()) {
                        batch.add(metadata(candidate.get(), seenAt));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                log.warn("Cannot index image file {}", absolutePath, exception);
                continue;
            }
            if (batch.size() >= batchSize) {
                batchWriter.upsert(batch);
                batch.clear();
            }
        }
        batchWriter.upsert(batch);
    }

    public void markUnavailable(Path absolutePath) {
        markUnavailableBatch(List.of(absolutePath));
    }

    public void markUnavailableBatch(Collection<Path> absolutePaths) {
        if (absolutePaths.isEmpty()) {
            return;
        }
        List<String> imageIds = new ArrayList<>(Math.min(batchSize, absolutePaths.size()));
        for (Path absolutePath : absolutePaths) {
            Path normalized = absolutePath.toAbsolutePath().normalize();
            if (!normalized.startsWith(imageRoot)) {
                continue;
            }
            Path relativePath = imageRoot.relativize(normalized);
            imageIds.add(ImageId.fromRelativePath(relativePath));
            if (imageIds.size() >= batchSize) {
                batchWriter.markUnavailable(imageIds);
                imageIds.clear();
            }
        }
        batchWriter.markUnavailable(imageIds);
    }

    private BatchResult reconcileBatch(
            List<ImageCandidate> candidates,
            Instant seenAt,
            boolean compareExisting
    ) {
        if (candidates.isEmpty()) {
            return new BatchResult(0, false);
        }
        Set<String> availableIds = compareExisting
                ? batchWriter.findAvailableIds(
                        candidates.stream().map(ImageCandidate::id).toList()
                )
                : Set.of();
        List<ImageMetadata> metadata = new ArrayList<>(candidates.size() - availableIds.size());
        boolean fileError = false;
        for (ImageCandidate candidate : candidates) {
            if (availableIds.contains(candidate.id())) {
                continue;
            }
            try {
                metadata.add(metadata(candidate, seenAt));
            } catch (IOException | RuntimeException exception) {
                fileError = true;
                log.warn("Cannot inspect image file {}", candidate.path(), exception);
            }
        }
        batchWriter.upsert(metadata);
        return new BatchResult(metadata.size(), fileError);
    }

    private long markMissingFiles() {
        String afterId = "";
        long missing = 0;
        while (true) {
            List<ImageAssetBatchWriter.AvailableImageFile> page =
                    batchWriter.findAvailableFilesAfter(afterId, batchSize);
            if (page.isEmpty()) {
                return missing;
            }
            List<String> missingIds = page.stream()
                    .filter(this::isMissing)
                    .map(ImageAssetBatchWriter.AvailableImageFile::id)
                    .toList();
            batchWriter.markUnavailable(missingIds);
            missing += missingIds.size();
            afterId = page.getLast().id();
            if (page.size() < batchSize) {
                return missing;
            }
        }
    }

    private boolean isMissing(ImageAssetBatchWriter.AvailableImageFile image) {
        Path path = imageRoot.resolve(image.relativePath()).normalize();
        if (!path.startsWith(imageRoot)) {
            log.warn("Stored image path escapes configured root: {}", image.relativePath());
            return true;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            return !attributes.isRegularFile();
        } catch (NoSuchFileException exception) {
            return true;
        } catch (IOException | SecurityException exception) {
            log.warn("Cannot verify stored image file {}; keeping it available", path, exception);
            return false;
        }
    }

    private java.util.Optional<ImageCandidate> candidate(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(imageRoot)) {
            return java.util.Optional.empty();
        }

        String fileName = normalized.getFileName().toString();
        ParsedFilename parsed = filenameParser.parse(fileName);
        if (parsed.gameCode() == null) {
            return java.util.Optional.empty();
        }
        Path relativePath = imageRoot.relativize(normalized);
        String normalizedRelativePath = relativePath.normalize().toString()
                .replace(File.separatorChar, '/');

        return java.util.Optional.of(new ImageCandidate(
                normalized,
                ImageId.fromRelativePath(relativePath),
                fileName,
                normalizedRelativePath,
                parsed
        ));
    }

    private ImageMetadata metadata(ImageCandidate candidate, Instant seenAt) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                candidate.path(),
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        ParsedFilename parsed = candidate.parsed();

        return new ImageMetadata(
                candidate.id(),
                candidate.fileName(),
                candidate.relativePath(),
                attributes.creationTime().toInstant(),
                attributes.lastModifiedTime().toInstant(),
                seenAt,
                seenAt,
                parsed.gameCode(),
                parsed.tokenId(),
                parsed.sessionUuid(),
                parsed.sessionId(),
                parsed.dealerCards(),
                parsed.activeUserCards(),
                parsed.inactiveUserCards(),
                parsed.payloadRaw(),
                parsed.buttonsRaw(),
                parsed.notification(),
                parsed.stand(),
                parsed.hit(),
                parsed.doubleAction(),
                parsed.split(),
                parsed.surrender(),
                parsed.processedAt(),
                parsed.recognitionDurationMs(),
                parsed.parseStatus()
        );
    }

    private record ImageCandidate(
            Path path,
            String id,
            String fileName,
            String relativePath,
            ParsedFilename parsed
    ) {
    }

    private record BatchResult(long written, boolean fileError) {
    }
}
