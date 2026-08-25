package com.introlabsystems.recognitionvalidator.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public interface CloudObjectStorage {

    void upload(String objectKey, Path source);

    boolean exists(String objectKey);

    URI presignGet(String objectKey, Duration ttl);

    CloudContent open(String objectKey);

    record CloudContent(InputStream stream, long contentLength) {
    }
}
