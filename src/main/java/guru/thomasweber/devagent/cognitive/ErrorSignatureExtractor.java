package guru.thomasweber.devagent.cognitive;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Normalizes compiler and linter error traces by masking volatile environment variables,
 * absolute filesystem paths, line/column coordinates, and timestamps (ADR-009).
 * Operates via a high-performance single-pass character scanner with ordered rules.
 */
@ApplicationScoped
public class ErrorSignatureExtractor {

    public static final int MAX_INPUT_CHARS = 65536;

    private static final String ADDR_REPLACEMENT = "@<ADDR>";
    private static final String TIME_REPLACEMENT = "<TIME>";
    private static final String DURATION_REPLACEMENT = "<DURATION>";
    private static final String LINE_COL_REPLACEMENT = ":<LINE>:<COL>";
    private static final String PATH_REPLACEMENT = "<PATH>";

    @FunctionalInterface
    private interface NormalizationRule {
        Match tryMatch(String input, int index, StringBuilder output);
    }

    private record Match(int length, String replacement) {}

    private final List<NormalizationRule> rules = List.of(
        this::tryMatchMemoryAddress,
        this::tryMatchIsoTimestamp,
        this::tryMatchClockTime,
        this::tryMatchDuration,
        this::tryMatchLineColumn,
        this::tryMatchPath
    );

    public String extractNormalizedText(String rawTrace) {
        if (rawTrace == null || rawTrace.isBlank()) {
            return "";
        }

        String input = (rawTrace.length() > MAX_INPUT_CHARS)
            ? rawTrace.substring(0, MAX_INPUT_CHARS)
            : rawTrace;

        StringBuilder sb = new StringBuilder(input.length());
        int i = 0;
        int n = input.length();
        boolean pendingSpace = false;

        while (i < n) {
            char c = input.charAt(i);

            if (Character.isWhitespace(c)) {
                if (!sb.isEmpty()) {
                    pendingSpace = true;
                }
                i++;
                continue;
            }

            Match match = null;
            for (NormalizationRule rule : rules) {
                match = rule.tryMatch(input, i, sb);
                if (match != null) {
                    break;
                }
            }

            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }

            if (match != null) {
                sb.append(match.replacement());
                i += match.length();
            } else {
                sb.append(c);
                i++;
            }
        }

        return sb.toString().trim();
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

    private Match tryMatchMemoryAddress(String s, int i, StringBuilder out) {
        if (s.charAt(i) != '@') {
            return null;
        }
        int j = i + 1;
        while (j < s.length() && isHexDigit(s.charAt(j))) {
            j++;
        }
        int hexCount = j - (i + 1);
        if (hexCount < 6 || hexCount > 16) {
            return null;
        }
        if (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) {
            return null;
        }
        if (isEmailDomainAfter(s, j)) {
            return null;
        }
        return new Match(1 + hexCount, ADDR_REPLACEMENT);
    }

    private static boolean isEmailDomainAfter(String s, int j) {
        if (j >= s.length() || s.charAt(j) != '.') {
            return false;
        }
        int k = j + 1;
        while (k < s.length() && Character.isLetter(s.charAt(k))) {
            k++;
        }
        int tldLen = k - (j + 1);
        return tldLen >= 2 && tldLen <= 10 && (k == s.length() || !Character.isLetterOrDigit(s.charAt(k)));
    }

    private Match tryMatchIsoTimestamp(String s, int i, StringBuilder out) {
        if (!matchPattern(s, i, "####-##-## ##:##:##")) {
            return null;
        }

        int idx = i + 19;
        if (idx < s.length() && s.charAt(idx) == '.') {
            idx++;
            while (idx < s.length() && isDigit(s.charAt(idx))) {
                idx++;
            }
        }
        if (idx < s.length()) {
            char tz = s.charAt(idx);
            if (tz == 'Z') {
                idx++;
            } else if (tz == '+' || tz == '-') {
                idx++;
                while (idx < s.length() && (isDigit(s.charAt(idx)) || s.charAt(idx) == ':')) {
                    idx++;
                }
            }
        }
        if (idx < s.length() && Character.isLetterOrDigit(s.charAt(idx))) {
            return null;
        }
        return new Match(idx - i, TIME_REPLACEMENT);
    }

    private Match tryMatchClockTime(String s, int i, StringBuilder out) {
        if (i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) {
            return null;
        }
        if (!matchPattern(s, i, "##:##:##")) {
            return null;
        }

        int idx = i + 8;
        if (idx < s.length() && s.charAt(idx) == '.') {
            idx++;
            while (idx < s.length() && isDigit(s.charAt(idx))) {
                idx++;
            }
        }
        if (idx < s.length() && Character.isLetterOrDigit(s.charAt(idx))) {
            return null;
        }
        return new Match(idx - i, TIME_REPLACEMENT);
    }

    private Match tryMatchDuration(String s, int i, StringBuilder out) {
        if (i > 0) {
            char prev = s.charAt(i - 1);
            if (Character.isLetterOrDigit(prev) || prev == '.') {
                return null;
            }
        }
        if (!isDigit(s.charAt(i))) {
            return null;
        }
        int idx = i;
        while (idx < s.length() && isDigit(s.charAt(idx))) {
            idx++;
        }
        if (idx + 1 < s.length() && s.charAt(idx) == '.' && isDigit(s.charAt(idx + 1))) {
            idx += 2;
            while (idx < s.length() && isDigit(s.charAt(idx))) {
                idx++;
            }
        }
        int unitStart = idx;
        while (unitStart < s.length() && (s.charAt(unitStart) == ' ' || s.charAt(unitStart) == '\t')) {
            unitStart++;
        }
        int unitLen = 0;
        if (startsWithIgnoreCase(s, unitStart, "milliseconds")) {
            unitLen = 12;
        } else if (startsWithIgnoreCase(s, unitStart, "seconds")) {
            unitLen = 7;
        } else if (startsWithIgnoreCase(s, unitStart, "ms")) {
            unitLen = 2;
        } else if (startsWithIgnoreCase(s, unitStart, "s")) {
            unitLen = 1;
        }
        if (unitLen == 0) {
            return null;
        }
        int end = unitStart + unitLen;
        if (end < s.length() && Character.isLetterOrDigit(s.charAt(end))) {
            return null;
        }
        return new Match(end - i, DURATION_REPLACEMENT);
    }

    private Match tryMatchLineColumn(String s, int i, StringBuilder out) {
        Match bracketMatch = tryMatchBracketLineCol(s, i);
        if (bracketMatch != null) {
            return bracketMatch;
        }
        Match wordMatch = tryMatchWordLineCol(s, i);
        if (wordMatch != null) {
            return wordMatch;
        }
        return tryMatchColonLineCol(s, i, out);
    }

    private Match tryMatchBracketLineCol(String s, int i) {
        int idx = i;
        if (s.startsWith(":[", idx)) {
            idx += 2;
        } else if (s.charAt(idx) == '[') {
            idx += 1;
        } else {
            return null;
        }
        int lineStart = idx;
        while (idx < s.length() && isDigit(s.charAt(idx))) {
            idx++;
        }
        if (idx == lineStart) {
            return null;
        }
        if (idx >= s.length() || s.charAt(idx) != ',') {
            return null;
        }
        idx++;
        while (idx < s.length() && Character.isWhitespace(s.charAt(idx))) {
            idx++;
        }
        int colStart = idx;
        while (idx < s.length() && isDigit(s.charAt(idx))) {
            idx++;
        }
        if (idx == colStart || idx >= s.length() || s.charAt(idx) != ']') {
            return null;
        }
        idx++;
        return new Match(idx - i, LINE_COL_REPLACEMENT);
    }

    private Match tryMatchWordLineCol(String s, int i) {
        if (i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) {
            return null;
        }
        if (!startsWithIgnoreCase(s, i, "line")) {
            return null;
        }
        int idx = i + 4;
        if (idx >= s.length() || !Character.isWhitespace(s.charAt(idx))) {
            return null;
        }
        while (idx < s.length() && Character.isWhitespace(s.charAt(idx))) {
            idx++;
        }
        int lineStart = idx;
        while (idx < s.length() && isDigit(s.charAt(idx))) {
            idx++;
        }
        if (idx == lineStart) {
            return null;
        }
        int end = idx;
        int check = idx;
        if (check < s.length() && checkChar(s.charAt(check), ',')) {
            check++;
        }
        while (check < s.length() && Character.isWhitespace(s.charAt(check))) {
            check++;
        }
        int colWordLen = 0;
        if (startsWithIgnoreCase(s, check, "column")) {
            colWordLen = 6;
        } else if (startsWithIgnoreCase(s, check, "col")) {
            colWordLen = 3;
        }
        if (colWordLen > 0) {
            check += colWordLen;
            while (check < s.length() && Character.isWhitespace(s.charAt(check))) {
                check++;
            }
            int colStart = check;
            while (check < s.length() && isDigit(s.charAt(check))) {
                check++;
            }
            if (check > colStart) {
                end = check;
            }
        }
        if (end < s.length() && Character.isLetterOrDigit(s.charAt(end))) {
            return null;
        }
        return new Match(end - i, LINE_COL_REPLACEMENT);
    }

    private Match tryMatchColonLineCol(String s, int i, StringBuilder out) {
        if (s.charAt(i) != ':') {
            return null;
        }
        boolean afterContext = outEndsWith(out, PATH_REPLACEMENT) || hasSourceExtensionBefore(s, i);
        if (!afterContext) {
            return null;
        }
        int idx = i + 1;
        while (idx < s.length() && isDigit(s.charAt(idx))) {
            idx++;
        }
        if (idx == i + 1) {
            return null;
        }
        if (idx < s.length() && s.charAt(idx) == ':' && idx + 1 < s.length() && isDigit(s.charAt(idx + 1))) {
            idx++;
            while (idx < s.length() && isDigit(s.charAt(idx))) {
                idx++;
            }
        }
        if (idx < s.length() && Character.isLetterOrDigit(s.charAt(idx))) {
            return null;
        }
        return new Match(idx - i, LINE_COL_REPLACEMENT);
    }

    private Match tryMatchPath(String s, int i, StringBuilder out) {
        Match winPath = tryMatchWindowsPath(s, i);
        if (winPath != null) {
            return winPath;
        }
        return tryMatchLinuxPath(s, i);
    }

    private Match tryMatchWindowsPath(String s, int i) {
        if (i + 3 >= s.length()) {
            return null;
        }
        char drive = s.charAt(i);
        if (!Character.isLetter(drive) || s.charAt(i + 1) != ':' || s.charAt(i + 2) != '\\') {
            return null;
        }
        int idx = i + 3;
        int lastSlash = i + 2;
        int lastDot = -1;
        while (idx < s.length()) {
            if (isPathTerminator(s, idx)) {
                break;
            }
            char c = s.charAt(idx);
            if (c == '\\') {
                lastSlash = idx;
            } else if (c == '.') {
                lastDot = idx;
            }
            idx++;
        }
        if (lastDot <= lastSlash || lastDot + 1 >= idx) {
            return null;
        }
        return new Match(idx - i, PATH_REPLACEMENT);
    }

    private Match tryMatchLinuxPath(String s, int i) {
        if (s.charAt(i) != '/') {
            return null;
        }
        if (i > 0) {
            char prev = s.charAt(i - 1);
            if (!Character.isWhitespace(prev) && prev != '[' && prev != '(' && prev != '\'' && prev != '"' && prev != ':') {
                return null;
            }
        }
        int idx = i;
        int lastSlash = i;
        int lastDot = -1;
        int slashCount = 0;

        while (idx < s.length()) {
            if (isPathTerminator(s, idx)) {
                break;
            }
            char c = s.charAt(idx);
            if (c == '/') {
                lastSlash = idx;
                slashCount++;
            } else if (c == '.') {
                lastDot = idx;
            } else if (!isPathChar(c)) {
                break;
            }
            idx++;
        }

        if (slashCount < 1 || lastDot <= lastSlash || lastDot + 1 >= idx) {
            return null;
        }
        return new Match(idx - i, PATH_REPLACEMENT);
    }

    private static boolean isPathTerminator(String s, int idx) {
        char c = s.charAt(idx);
        if (Character.isWhitespace(c) || c == '[') {
            return true;
        }
        if (c == ':' && idx + 1 < s.length()) {
            char next = s.charAt(idx + 1);
            return next == '[' || isDigit(next);
        }
        return false;
    }

    private static boolean matchPattern(String s, int offset, String pattern) {
        if (offset + pattern.length() > s.length()) {
            return false;
        }
        for (int k = 0; k < pattern.length(); k++) {
            char p = pattern.charAt(k);
            char c = s.charAt(offset + k);
            if (p == '#') {
                if (!isDigit(c)) {
                    return false;
                }
            } else if (p == ' ') {
                if (c != ' ' && c != 'T') {
                    return false;
                }
            } else if (c != p) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isPathChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }

    private static boolean checkChar(char actual, char expected) {
        return actual == expected;
    }

    private static boolean startsWithIgnoreCase(String s, int offset, String prefix) {
        if (offset + prefix.length() > s.length()) {
            return false;
        }
        for (int k = 0; k < prefix.length(); k++) {
            char c1 = Character.toLowerCase(s.charAt(offset + k));
            char c2 = Character.toLowerCase(prefix.charAt(k));
            if (c1 != c2) {
                return false;
            }
        }
        return true;
    }

    private static boolean outEndsWith(StringBuilder sb, String suffix) {
        if (sb.length() < suffix.length()) {
            return false;
        }
        int start = sb.length() - suffix.length();
        for (int k = 0; k < suffix.length(); k++) {
            if (sb.charAt(start + k) != suffix.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSourceExtensionBefore(String s, int index) {
        int k = index - 1;
        int extLen = 0;
        while (k >= 0 && extLen <= 8) {
            char c = s.charAt(k);
            if (c == '.') {
                return extLen >= 1 && k > 0 && Character.isLetterOrDigit(s.charAt(k - 1));
            }
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
            extLen++;
            k--;
        }
        return false;
    }
}
