package com.github.h3yow.buildreleaseorchestratorjava.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 based checksum helper. Every artifact and cache payload is addressed
 * by the checksum of its local, deterministically generated content, so the
 * provenance of a produced binary can always be verified later.
 */
public final class Checksums {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Checksums() {
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
