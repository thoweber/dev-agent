package guru.thomasweber.devagent.workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Deterministic cryptographic digest helpers used across signature extraction and caching.
 */
public final class DigestUtils {

    private DigestUtils() {
    }

    public static String sha256Hex(String input) {
        String safeInput = Objects.requireNonNullElse(input, "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safeInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest algorithm not available", e);
        }
    }
}
