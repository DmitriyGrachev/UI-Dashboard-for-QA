package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jpa.ImageAssetRepository;
import com.introlabsystems.recognitionvalidator.exception.ImageNotFoundException;
import com.introlabsystems.recognitionvalidator.exception.ImageStorageUnavailableException;
import com.introlabsystems.recognitionvalidator.model.entity.ImageAsset;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class ImageStorageServiceImpl implements ImageStorageService {

    private final Path imageRoot;
    private final ImageAssetRepository images;
    private final B2StorageProperties b2Properties;
    private final CloudObjectStorage cloudStorage;
    private final Clock clock;

    public ImageStorageServiceImpl(
            ValidatorProperties properties,
            ImageAssetRepository images,
            B2StorageProperties b2Properties,
            ObjectProvider<CloudObjectStorage> cloudStorage,
            Clock clock
    ) {
        this.imageRoot = properties.imageRoot().toAbsolutePath().normalize();
        this.images = images;
        this.b2Properties = b2Properties;
        this.cloudStorage = cloudStorage.getIfAvailable();
        this.clock = clock;
    }

    @Override
    @Transactional(noRollbackFor = {
            ImageNotFoundException.class,
            ImageStorageUnavailableException.class
    })
    public BrowserDelivery openForBrowser(String imageId) {
        ImageAsset asset = findAsset(imageId);
        boolean localAvailable = isLocalAvailable(asset);
        boolean cloudAvailable = hasCloud(asset);
        if (!localAvailable) {
            markUnavailable(asset);
        }

        if (localAvailable && (!cloudAvailable || isFresh(asset))) {
            Optional<ImageContent> local = openLocal(asset);
            if (local.isPresent()) {
                return new BrowserDelivery.Local(local.orElseThrow());
            }
            localAvailable = false;
        }
        if (cloudAvailable && cloudStorage != null) {
            return new BrowserDelivery.Redirect(presign(asset.getCloudObjectKey()));
        }
        if (localAvailable) {
            Optional<ImageContent> local = openLocal(asset);
            if (local.isPresent()) {
                return new BrowserDelivery.Local(local.orElseThrow());
            }
        }

        markUnavailable(asset);
        throw new ImageNotFoundException(imageId);
    }

    @Override
    @Transactional(noRollbackFor = ImageStorageUnavailableException.class)
    public boolean verifyForBrowser(String imageId) {
        ImageAsset asset = findAsset(imageId);
        boolean localAvailable = isLocalAvailable(asset);
        boolean cloudAvailable = hasCloud(asset);
        if (!localAvailable) {
            markUnavailable(asset);
        }

        if (localAvailable && (!cloudAvailable || isFresh(asset))) {
            return true;
        }
        if (cloudAvailable && cloudStorage != null) {
            if (cloudExists(asset.getCloudObjectKey())) {
                return true;
            }
            asset.clearCloudStorage();
            if (isLocalAvailable(asset)) {
                return true;
            }
            markUnavailable(asset);
            return false;
        }
        return localAvailable && isLocalAvailable(asset);
    }

    @Override
    @Transactional(noRollbackFor = {
            ImageNotFoundException.class,
            ImageStorageUnavailableException.class
    })
    public ImageContent open(String imageId) {
        ImageAsset asset = findAsset(imageId);
        boolean localAvailable = isLocalAvailable(asset);
        boolean cloudAvailable = hasCloud(asset);
        if (!localAvailable) {
            markUnavailable(asset);
        }

        if (localAvailable && (!cloudAvailable || isFresh(asset))) {
            Optional<ImageContent> local = openLocal(asset);
            if (local.isPresent()) {
                return local.orElseThrow();
            }
            localAvailable = false;
        }
        if (cloudAvailable && cloudStorage != null) {
            return openCloud(asset);
        }
        if (localAvailable) {
            Optional<ImageContent> local = openLocal(asset);
            if (local.isPresent()) {
                return local.orElseThrow();
            }
        }

        markUnavailable(asset);
        throw new ImageNotFoundException(imageId);
    }

    private ImageAsset findAsset(String imageId) {
        return images.findById(imageId)
                .orElseThrow(() -> new ImageNotFoundException(imageId));
    }

    private Optional<ImageContent> openLocal(ImageAsset asset) {
        Path file = resolveSafeFile(asset);
        if (!isLocalAvailable(asset, file)) {
            markUnavailable(asset);
            return Optional.empty();
        }

        try {
            long length = Files.size(file);
            InputStream stream = Files.newInputStream(file);
            return Optional.of(new ImageContent(
                    new InputStreamResource(stream),
                    length,
                    asset.getFileName()
            ));
        } catch (IOException exception) {
            markUnavailable(asset);
            return Optional.empty();
        }
    }

    private boolean isLocalAvailable(ImageAsset asset) {
        return isLocalAvailable(asset, resolveSafeFile(asset));
    }

    private boolean isLocalAvailable(ImageAsset asset, Path file) {
        return file != null
                && asset.isFileAvailable()
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private Path resolveSafeFile(ImageAsset asset) {
        try {
            Path file = imageRoot.resolve(asset.getRelativePath()).normalize();
            if (!file.startsWith(imageRoot)) {
                return null;
            }
            Path current = imageRoot;
            for (Path part : imageRoot.relativize(file)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) {
                    return null;
                }
            }
            return file;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private boolean hasCloud(ImageAsset asset) {
        return asset.getCloudObjectKey() != null
                && !asset.getCloudObjectKey().isBlank()
                && asset.getCloudUploadedAt() != null
                && clock.instant().isBefore(
                        asset.getCloudUploadedAt().plus(b2Properties.metadataRetention())
                );
    }

    private boolean isFresh(ImageAsset asset) {
        Instant preferredUntil = asset.getFileCreatedAt().plus(b2Properties.localPreferredAge());
        return clock.instant().isBefore(preferredUntil);
    }

    private java.net.URI presign(String objectKey) {
        try {
            return cloudStorage.presignGet(objectKey, b2Properties.presignedUrlTtl());
        } catch (SdkException exception) {
            throw new ImageStorageUnavailableException(exception);
        }
    }

    private boolean cloudExists(String objectKey) {
        try {
            return cloudStorage.exists(objectKey);
        } catch (SdkException exception) {
            throw new ImageStorageUnavailableException(exception);
        }
    }

    private ImageContent openCloud(ImageAsset asset) {
        try {
            CloudObjectStorage.CloudContent content = cloudStorage.open(asset.getCloudObjectKey());
            return new ImageContent(
                    new InputStreamResource(new CloudInputStream(content.stream())),
                    content.contentLength(),
                    asset.getFileName()
            );
        } catch (NoSuchKeyException exception) {
            asset.clearCloudStorage();
            throw new ImageNotFoundException(asset.getId());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                asset.clearCloudStorage();
                throw new ImageNotFoundException(asset.getId());
            }
            throw new ImageStorageUnavailableException(exception);
        } catch (SdkException exception) {
            throw new ImageStorageUnavailableException(exception);
        }
    }

    private void markUnavailable(ImageAsset asset) {
        if (asset.isFileAvailable()) {
            images.markUnavailableById(asset.getId());
        }
    }

    private static final class CloudInputStream extends FilterInputStream {

        private CloudInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (SdkException exception) {
                throw new ImageStorageUnavailableException(exception);
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                return super.read(bytes, offset, length);
            } catch (SdkException exception) {
                throw new ImageStorageUnavailableException(exception);
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                return super.skip(count);
            } catch (SdkException exception) {
                throw new ImageStorageUnavailableException(exception);
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return super.available();
            } catch (SdkException exception) {
                throw new ImageStorageUnavailableException(exception);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } catch (SdkException exception) {
                throw new ImageStorageUnavailableException(exception);
            }
        }
    }
}
