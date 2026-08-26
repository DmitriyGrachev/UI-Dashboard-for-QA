package com.introlabsystems.recognitionvalidator.storage;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;

public final class B2S3ObjectStorage implements CloudObjectStorage {

    private static final Duration MAX_PRESIGNED_URL_TTL = Duration.ofDays(7);

    private final S3Client client;
    private final S3Presigner presigner;
    private final B2StorageProperties properties;

    public B2S3ObjectStorage(
            S3Client client,
            S3Presigner presigner,
            B2StorageProperties properties
    ) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public void upload(String objectKey, Path source) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .contentType("image/png")
                        .build(),
                RequestBody.fromFile(source)
        );
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw exception;
        }
    }

    @Override
    public URI presignGet(String objectKey, Duration ttl) {
        validateTtl(ttl);
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build())
                .build();
        try {
            return presigner.presignGetObject(request).url().toURI();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Cannot create B2 pre-signed URL", exception);
        }
    }

    @Override
    public CloudContent open(String objectKey) {
        ResponseInputStream<GetObjectResponse> stream = client.getObject(
                GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build()
        );
        Long contentLength = stream.response().contentLength();
        return new CloudContent(stream, contentLength == null ? -1L : contentLength);
    }

    private static void validateTtl(Duration ttl) {
        if (ttl == null
                || ttl.isNegative()
                || ttl.isZero()
                || ttl.compareTo(MAX_PRESIGNED_URL_TTL) > 0) {
            throw new IllegalArgumentException(
                    "Pre-signed URL TTL must be between one second and seven days"
            );
        }
    }
}
