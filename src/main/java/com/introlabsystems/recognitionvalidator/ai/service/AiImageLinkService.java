package com.introlabsystems.recognitionvalidator.ai.service;

import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import com.introlabsystems.recognitionvalidator.ai.security.AiLocalImageUrlSigner;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jpa.ImageAssetRepository;
import com.introlabsystems.recognitionvalidator.model.entity.ImageAsset;
import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Repository reads end before filesystem inspection or presigning. No delivery transaction. */
@Service
public class AiImageLinkService {
    private final ImageAssetRepository images;
    private final B2StorageProperties b2;
    private final CloudObjectStorage cloud;
    private final AiLocalImageUrlSigner signer;
    private final Path root;
    private final String publicBaseUrl;
    private final Clock clock;

    public AiImageLinkService(ImageAssetRepository images, B2StorageProperties b2, ObjectProvider<CloudObjectStorage> cloud,
                              AiLocalImageUrlSigner signer, ValidatorProperties properties, Clock clock,
                              @Value("${validator.ai-delivery.public-base-url:}") String publicBaseUrl) {
        this.images = images;
        this.b2 = b2;
        this.cloud = cloud.getIfAvailable();
        this.signer = signer;
        this.root = properties.imageRoot().toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl;
        this.clock = clock;
    }

    public void validateConfiguration() {
        if (!(b2.enabled() && cloud != null) && !localConfigured()) throw configurationError();
    }

    public URI create(String imageId, Instant leaseExpiresAt) {
        ImageAsset image = images.findById(imageId).orElseThrow(AiImageLinkService::unavailable);
        Instant now = clock.instant();
        boolean local = localAvailable(image);
        boolean remote = b2.enabled() && cloud != null && image.getCloudObjectKey() != null
                && !image.getCloudObjectKey().isBlank() && image.getCloudUploadedAt() != null
                && image.getCloudUploadedAt().plus(b2.metadataRetention()).isAfter(now);
        boolean fresh = image.getFileCreatedAt().plus(b2.localPreferredAge()).isAfter(now);
        if (local && localConfigured() && (!remote || fresh)) return localUrl(imageId, leaseExpiresAt);
        if (remote) {
            Duration minimum = Duration.between(now, leaseExpiresAt.plusSeconds(30));
            Duration ttl = b2.presignedUrlTtl().compareTo(minimum) >= 0 ? b2.presignedUrlTtl() : minimum;
            try { return cloud.presignGet(image.getCloudObjectKey(), ttl); }
            catch (SdkException e) { throw unavailable(); }
        }
        if (local) throw configurationError();
        throw unavailable();
    }

    private URI localUrl(String id, Instant leaseExpiresAt) {
        long expires = leaseExpiresAt.plusSeconds(30).getEpochSecond();
        String origin = publicBaseUrl.replaceAll("/$", "");
        return URI.create(origin + AiLocalImageUrlSigner.path(id) + "?expires=" + expires + "&signature=" + signer.sign(id, expires));
    }
    private boolean localConfigured() {
        if (!signer.configured()) return false;
        try {
            URI uri = URI.create(publicBaseUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && (uri.getPath().isEmpty() || uri.getPath().equals("/"));
        } catch (IllegalArgumentException e) { return false; }
    }
    private boolean localAvailable(ImageAsset image) {
        if (!image.isFileAvailable()) return false;
        try {
            Path path = root.resolve(image.getRelativePath()).normalize();
            if (!path.startsWith(root)) return false;
            Path current = root;
            for (Path part : root.relativize(path)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) return false;
            }
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IllegalArgumentException e) { return false; }
    }
    private static AiQueueException configurationError() {
        return new AiQueueException(HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_NOT_CONFIGURED", "Configure B2 or HTTPS public origin and local signing key");
    }
    private static AiQueueException unavailable() {
        return new AiQueueException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_UNAVAILABLE", "Image source is temporarily unavailable");
    }
}
