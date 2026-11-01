package guru.thomasweber.devagent.cognitive;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Normalizes compiler and linter error traces by masking volatile environment variables,
 * absolute filesystem paths, line/column coordinates, and timestamps (ADR-009).
 */
@ApplicationScoped
public class ErrorSignatureExtractor {

    private static final Pattern MEMORY_ADDR_PATTERN = Pattern.compile("@[0-9a-fA-F]{6,16}");
    private static final Pattern ISO_TIMESTAMP_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern CLOCK_TIME_PATTERN = Pattern.compile("\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b");
    private static final Pattern DURATION_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\s*(?:ms|s|seconds|milliseconds)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINUX_PATH_PATTERN = Pattern.compile("(?:/[a-zA-Z0-9_.-]+)+/[a-zA-Z0-9_.-]+\\.[a-zA-Z0-9]+");
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("[a-zA-Z]:\\\\\\S+\\.[a-zA-Z0-9]+");
    private static final Pattern LINE_COL_BRACKET_PATTERN = Pattern.compile(":\\[\\d+,\\s*\\d+\\]|\\[\\d+,\\s*\\d+\\]");
    private static final Pattern LINE_COL_COLON_PATTERN = Pattern.compile("(?::|\\bline\\s+)\\d+(?::|,?\\s*col(?:umn)?\\s*\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    public String extractNormalizedText(String rawTrace) {
        if (rawTrace == null || rawTrace.isBlank()) {
            return "";
        }

        String s = rawTrace;
        s = MEMORY_ADDR_PATTERN.matcher(s).replaceAll("@<ADDR>");
        s = ISO_TIMESTAMP_PATTERN.matcher(s).replaceAll("<TIME>");
        s = CLOCK_TIME_PATTERN.matcher(s).replaceAll("<TIME>");
        s = DURATION_PATTERN.matcher(s).replaceAll("<DURATION>");
        s = LINUX_PATH_PATTERN.matcher(s).replaceAll("<PATH>");
        s = WINDOWS_PATH_PATTERN.matcher(s).replaceAll("<PATH>");
        s = LINE_COL_BRACKET_PATTERN.matcher(s).replaceAll(":<LINE>:<COL>");
        s = LINE_COL_COLON_PATTERN.matcher(s).replaceAll(":<LINE>:<COL>");
        s = WHITESPACE_PATTERN.matcher(s).replaceAll(" ").trim();

        return s;
    }

    public String extractSignature(String rawTrace) {
        String normalized = extractNormalizedText(rawTrace);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest algorithm not available", e);
        }
    }
}
