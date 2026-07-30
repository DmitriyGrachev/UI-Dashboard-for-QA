package com.introlabsystems.recognitionvalidator.image;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Service
public class ImageStorageService {

    private final Path imageRoot;
    private final ImageAssetRepository images;

    public ImageStorageService(
            ValidatorProperties properties,
            ImageAssetRepository images
    ) {
        this.imageRoot = properties.imageRoot().toAbsolutePath().normalize();
        this.images = images;
    }

    @Transactional
    public ImageContent open(String imageId) {
        ImageAsset asset = images.findById(imageId)
                .orElseThrow(() -> new ImageNotFoundException(imageId));
        Path file = imageRoot.resolve(asset.getRelativePath()).normalize();

        if (!file.startsWith(imageRoot)
                || !asset.isFileAvailable()
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            markUnavailable(asset);
            throw new ImageNotFoundException(imageId);
        }

        try {
            long length = Files.size(file);
            InputStream stream = Files.newInputStream(file);
            return new ImageContent(
                    new InputStreamResource(stream),
                    length,
                    asset.getFileName()
            );
        } catch (IOException exception) {
            markUnavailable(asset);
            throw new ImageNotFoundException(imageId);
        }
    }

    private void markUnavailable(ImageAsset asset) {
        if (asset.isFileAvailable()) {
            images.markUnavailableById(asset.getId());
        }
    }

    public record ImageContent(
            InputStreamResource resource,
            long contentLength,
            String fileName
    ) {
    }
}
