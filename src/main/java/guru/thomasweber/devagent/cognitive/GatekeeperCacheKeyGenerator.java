package guru.thomasweber.devagent.cognitive;

import io.quarkus.cache.CacheKeyGenerator;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic cache key generator for Architectural Gatekeeper evaluations (ADR-009).
 * Computes: SHA-256(UnifiedDiff + RetrievedAdrsDigest)
 */
@ApplicationScoped
public class GatekeeperCacheKeyGenerator implements CacheKeyGenerator {

    private static final String DELIMITER = "\n---ADR_DIGEST---\n";

    @Override
    public Object generate(Method method, Object... methodParams) {
        String diff = "";
        String adrs = "";

        if (methodParams != null && methodParams.length > 0) {
            if (methodParams[0] != null) {
                diff = methodParams[0].toString().strip();
            }
            if (methodParams.length > 1 && methodParams[1] != null) {
                adrs = methodParams[1].toString().strip();
            }
        }

        String compositePayload = diff + DELIMITER + adrs;
        return sha256Hex(compositePayload);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest algorithm not available", e);
        }
    }
}
