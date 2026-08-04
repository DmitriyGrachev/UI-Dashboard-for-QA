package com.introlabsystems.recognitionvalidator.service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;

public interface RejectedScreenshotExportService {

    int writeZip(
            Instant processedFrom,
            Instant processedTo,
            boolean includePreviouslyDownloaded,
            OutputStream output
    ) throws IOException;
}
