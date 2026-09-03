package com.introlabsystems.recognitionvalidator.ai.security;

import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

public class AiLocalImageUrlSigner {
    private final byte[] key;
    private final Clock clock;

    public AiLocalImageUrlSigner(@Value("${validator.ai-delivery.signing-key:}") String key, Clock clock) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }
    public boolean configured() { return key.length >= 32; }
    public static String path(String imageId) {
        if (imageId == null || !imageId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid image ID");
        return "/api/integration/images/" + imageId + "/content";
    }
    public String sign(String imageId, long expires) {
        if (!configured()) throw new IllegalStateException("Local image signing is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("GET\n" + path(imageId) + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Cannot sign image link", e); }
    }
    public boolean verify(String imageId, String expires, String signature) {
        if (!configured() || signature == null || !signature.matches("[0-9a-f]{64}") || expires == null) return false;
        try {
            long seconds = Long.parseLong(expires);
            return seconds > clock.instant().getEpochSecond() && MessageDigest.isEqual(
                    HexFormat.of().parseHex(signature), HexFormat.of().parseHex(sign(imageId, seconds)));
        } catch (IllegalArgumentException e) { return false; }
    }
}
