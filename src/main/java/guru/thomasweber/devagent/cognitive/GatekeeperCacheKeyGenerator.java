package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import io.quarkus.cache.CacheKeyGenerator;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic cache key generator for Architectural Gatekeeper evaluations (ADR-009).
 * Computes: SHA-256(UnifiedDiff + RetrievedAdrsDigest)
 * When passed a List of ArchitecturalRecord, extracts and sorts rule IDs deterministically.
 */
@ApplicationScoped
public class GatekeeperCacheKeyGenerator implements CacheKeyGenerator {

    @Override
    public Object generate(Method method, Object... methodParams) {
        String diff = extractDiff(methodParams);
        String adrs = extractAdrs(methodParams);
        String compositePayload = "DIFF:" + diff.length() + ":" + diff + "\nADRS:" + adrs.length() + ":" + adrs;
        return sha256Hex(compositePayload);
    }

    private static String extractDiff(Object[] methodParams) {
        if (methodParams != null && methodParams.length > 0 && methodParams[0] != null) {
            return methodParams[0].toString().strip();
        }
        return "";
    }

    private static String extractAdrs(Object[] methodParams) {
        if (methodParams == null || methodParams.length <= 1 || methodParams[1] == null) {
            return "";
        }
        Object second = methodParams[1];
        if (second instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    items.add(formatRecordItem(item));
                }
            }
            items.sort(null);
            return String.join("\n---\n", items);
        }
        return second.toString().strip();
    }

    private static String formatRecordItem(Object item) {
        if (item instanceof ArchitecturalRecord rec) {
            String summary = rec.summary() != null ? rec.summary().strip() : "";
            String content = rec.content() != null ? rec.content().strip() : "";
            return frame(String.valueOf(rec.id()))
                + frame(String.valueOf(rec.title()))
                + frame(String.valueOf(rec.status()))
                + frame(summary)
                + frame(content);
        }
        return item.toString().strip();
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
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
