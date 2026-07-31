package com.introlabsystems.recognitionvalidator.image;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
        List<ImageMetadata> batch = new ArrayList<>(batchSize);
        boolean traversalComplete = false;
        boolean fileError = false;

        try {
            Files.createDirectories(imageRoot);
            try (Stream<Path> paths = Files.list(imageRoot)) {
                Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    try {
                        metadata(path, scanStartedAt).ifPresent(batch::add);
                        if (batch.size() >= batchSize) {
                            batchWriter.upsert(batch);
                            batch.clear();
                        }
                    } catch (IOException | RuntimeException exception) {
                        fileError = true;
                        log.warn("Cannot inspect image file {}", path, exception);
                    }
                }
            }
            batchWriter.upsert(batch);
            traversalComplete = true;
        } catch (IOException | RuntimeException exception) {
            log.error("Cannot complete image directory scan for {}", imageRoot, exception);
        }

        if (traversalComplete && !fileError) {
            batchWriter.markMissingBefore(scanStartedAt);
        }
    }

    public void index(Path absolutePath) {
        Instant seenAt = clock.instant();
        try {
            if (Files.isRegularFile(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
                metadata(absolutePath, seenAt)
                        .ifPresent(value -> batchWriter.upsert(List.of(value)));
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Cannot index image file {}", absolutePath, exception);
        }
    }

    public void markUnavailable(Path absolutePath) {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        if (!normalized.startsWith(imageRoot)) {
            return;
        }
        Path relativePath = imageRoot.relativize(normalized);
        batchWriter.markUnavailable(ImageId.fromRelativePath(relativePath));
    }

    private java.util.Optional<ImageMetadata> metadata(Path path, Instant seenAt) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(imageRoot)) {
            return java.util.Optional.empty();
        }

        String fileName = normalized.getFileName().toString();
        ParsedFilename parsed = filenameParser.parse(fileName);
        if (parsed.gameCode() == null) {
            return java.util.Optional.empty();
        }

        BasicFileAttributes attributes = Files.readAttributes(
                normalized,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        Path relativePath = imageRoot.relativize(normalized);
        String normalizedRelativePath = relativePath.normalize().toString()
                .replace(File.separatorChar, '/');

        return java.util.Optional.of(new ImageMetadata(
                ImageId.fromRelativePath(relativePath),
                fileName,
                normalizedRelativePath,
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
        ));
    }
}
