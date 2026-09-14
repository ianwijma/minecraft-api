package dev.example.mapi.internal.config;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Secure bearer-token generation for first-run configs (spec: credentials
 * are config-backed; a fresh instance must never ship with an empty token).
 * 32 random bytes, base64url-encoded (43 chars, comfortably above
 * {@link MapiConfig#MIN_TOKEN_LENGTH}). Secrets are never logged.
 */
public final class MapiTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private MapiTokens() {
    }

    /** @return a fresh base64url token (43 characters), never blank */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
