package com.introlabsystems.recognitionvalidator.image;

import java.util.UUID;

public record ParsedFilename(
        String gameCode,
        Long tokenId,
        UUID sessionUuid,
        String sessionId,
        RecognitionResult recognition
) {

    public static ParsedFilename error() {
        return error(null);
    }

    public static ParsedFilename error(String gameCode) {
        return new ParsedFilename(
                gameCode, null, null, null,
                RecognitionResult.error()
        );
    }
}
