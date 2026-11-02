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

    private final Normalizer normalizer = new Normalizer();

    public String extractNormalizedText(String rawTrace) {
        return normalizer.normalize(rawTrace);
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

    @FunctionalInterface
    private interface NormalizationRule {
        Match tryMatch(ScanCursor cursor);
    }

    private record Match(int endIndex, String replacement) {}

    private static final class Normalizer {
        private final List<NormalizationRule> rules = List.of(
            new MemoryAddressRule(),
            new TimestampRule(),
            new ClockTimeRule(),
            new DurationRule(),
            new LineColumnRule(),
            new PathRule()
        );

        public String normalize(String rawTrace) {
            if (rawTrace == null || rawTrace.isBlank()) {
                return "";
            }

            String input = (rawTrace.length() > MAX_INPUT_CHARS)
                ? rawTrace.substring(0, MAX_INPUT_CHARS)
                : rawTrace;

            StringBuilder sb = new StringBuilder(input.length());
            ScanCursor cursor = new ScanCursor(input, sb);
            boolean pendingSpace = false;

            while (!cursor.isEof()) {
                char c = cursor.current();

                if (Character.isWhitespace(c)) {
                    if (!sb.isEmpty()) {
                        pendingSpace = true;
                    }
                    cursor.advance();
                    continue;
                }

                Match match = null;
                for (NormalizationRule rule : rules) {
                    match = rule.tryMatch(cursor);
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
                    cursor.setOffset(match.endIndex());
                } else {
                    sb.append(c);
                    cursor.advance();
                }
            }

            return sb.toString().trim();
        }
    }

    private static final class ScanCursor {
        private final String input;
        private final StringBuilder output;
        private int offset;

        private ScanCursor(String input, StringBuilder output) {
            this.input = input;
            this.output = output;
            this.offset = 0;
        }

        public int offset() {
            return offset;
        }

        public void setOffset(int offset) {
            this.offset = offset;
        }

        public void advance() {
            this.offset++;
        }

        public boolean isEof() {
            return offset >= input.length();
        }

        public int length() {
            return input.length();
        }

        public char current() {
            return input.charAt(offset);
        }

        public char charAt(int index) {
            return input.charAt(index);
        }

        public boolean hasRemaining(int count) {
            return offset + count <= input.length();
        }

        public boolean hasChar(int index, char expected) {
            return index >= 0 && index < input.length() && input.charAt(index) == expected;
        }

        public boolean isDigit(int index) {
            return index >= 0 && index < input.length() && Character.isDigit(input.charAt(index));
        }

        public boolean isWhitespace(int index) {
            return index >= 0 && index < input.length() && Character.isWhitespace(input.charAt(index));
        }

        public boolean isTokenBoundary(int index) {
            return index >= input.length() || !Character.isLetterOrDigit(input.charAt(index));
        }

        public int readDigits(int from) {
            int idx = from;
            while (idx < input.length() && Character.isDigit(input.charAt(idx))) {
                idx++;
            }
            return idx;
        }

        public int readHexDigits(int from) {
            int idx = from;
            while (idx < input.length() && isHexDigit(input.charAt(idx))) {
                idx++;
            }
            return idx;
        }

        public int readDecimalNumber(int from) {
            int idx = readDigits(from);
            if (idx > from && hasChar(idx, '.') && isDigit(idx + 1)) {
                idx = readDigits(idx + 2);
            }
            return idx;
        }

        public int skipInlineWhitespace(int from) {
            int idx = from;
            while (idx < input.length() && (input.charAt(idx) == ' ' || input.charAt(idx) == '\t')) {
                idx++;
            }
            return idx;
        }

        public int skipWhitespace(int from) {
            int idx = from;
            while (idx < input.length() && Character.isWhitespace(input.charAt(idx))) {
                idx++;
            }
            return idx;
        }

        public boolean startsWith(String prefix, int from) {
            return input.startsWith(prefix, from);
        }

        public boolean startsWithIgnoreCase(String prefix, int from) {
            if (from + prefix.length() > input.length()) {
                return false;
            }
            for (int k = 0; k < prefix.length(); k++) {
                char c1 = Character.toLowerCase(input.charAt(from + k));
                char c2 = Character.toLowerCase(prefix.charAt(k));
                if (c1 != c2) {
                    return false;
                }
            }
            return true;
        }

        public boolean matchPattern(String pattern, int from) {
            if (from + pattern.length() > input.length()) {
                return false;
            }
            for (int k = 0; k < pattern.length(); k++) {
                char p = pattern.charAt(k);
                char c = input.charAt(from + k);
                if (p == '#') {
                    if (!Character.isDigit(c)) {
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

        public boolean outputEndsWith(String suffix) {
            if (output.length() < suffix.length()) {
                return false;
            }
            int start = output.length() - suffix.length();
            for (int k = 0; k < suffix.length(); k++) {
                if (output.charAt(start + k) != suffix.charAt(k)) {
                    return false;
                }
            }
            return true;
        }

        public boolean hasSourceExtensionBefore(int index) {
            int k = index - 1;
            int extLen = 0;
            while (k >= 0 && extLen <= 8) {
                char c = input.charAt(k);
                if (c == '.') {
                    return extLen >= 1 && k > 0 && Character.isLetterOrDigit(input.charAt(k - 1));
                }
                if (!Character.isLetterOrDigit(c) && c != '_') {
                    return false;
                }
                extLen++;
                k--;
            }
            return false;
        }

        public boolean isEmailDomainAfter(int j) {
            if (!hasChar(j, '.')) {
                return false;
            }
            int k = j + 1;
            while (k < input.length() && Character.isLetter(input.charAt(k))) {
                k++;
            }
            int tldLen = k - (j + 1);
            return tldLen >= 2 && tldLen <= 10 && isTokenBoundary(k);
        }

        public boolean isPathTerminator(int idx) {
            if (idx >= input.length()) {
                return true;
            }
            char c = input.charAt(idx);
            if (Character.isWhitespace(c) || c == '[') {
                return true;
            }
            if (c == ':' && idx + 1 < input.length()) {
                char next = input.charAt(idx + 1);
                return next == '[' || Character.isDigit(next);
            }
            return false;
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }
    }

    private static final class MemoryAddressRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            if (cursor.current() != '@') {
                return null;
            }
            int start = cursor.offset();
            int endHex = cursor.readHexDigits(start + 1);
            int hexCount = endHex - (start + 1);
            if (hexCount < 6 || hexCount > 16) {
                return null;
            }
            if (!cursor.isTokenBoundary(endHex)) {
                return null;
            }
            if (cursor.isEmailDomainAfter(endHex)) {
                return null;
            }
            return new Match(endHex, ADDR_REPLACEMENT);
        }
    }

    private static final class TimestampRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            int start = cursor.offset();
            if (!cursor.matchPattern("####-##-## ##:##:##", start)) {
                return null;
            }

            int idx = start + 19;
            if (cursor.hasChar(idx, '.')) {
                idx = cursor.readDigits(idx + 1);
            }
            if (idx < cursor.length()) {
                char tz = cursor.charAt(idx);
                if (tz == 'Z') {
                    idx++;
                } else if (tz == '+' || tz == '-') {
                    idx++;
                    while (idx < cursor.length() && (cursor.isDigit(idx) || cursor.hasChar(idx, ':'))) {
                        idx++;
                    }
                }
            }
            if (!cursor.isTokenBoundary(idx)) {
                return null;
            }
            return new Match(idx, TIME_REPLACEMENT);
        }
    }

    private static final class ClockTimeRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            int start = cursor.offset();
            if (start > 0 && !cursor.isTokenBoundary(start - 1)) {
                return null;
            }
            if (!cursor.matchPattern("##:##:##", start)) {
                return null;
            }

            int idx = start + 8;
            if (cursor.hasChar(idx, '.')) {
                idx = cursor.readDigits(idx + 1);
            }
            if (!cursor.isTokenBoundary(idx)) {
                return null;
            }
            return new Match(idx, TIME_REPLACEMENT);
        }
    }

    private static final class DurationRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            int start = cursor.offset();
            if (start > 0) {
                char prev = cursor.charAt(start - 1);
                if (Character.isLetterOrDigit(prev) || prev == '.') {
                    return null;
                }
            }
            if (!cursor.isDigit(start)) {
                return null;
            }
            int numberEnd = cursor.readDecimalNumber(start);
            int unitStart = cursor.skipInlineWhitespace(numberEnd);
            int unitLen = matchDurationUnit(cursor, unitStart);
            if (unitLen == 0) {
                return null;
            }
            int end = unitStart + unitLen;
            if (!cursor.isTokenBoundary(end)) {
                return null;
            }
            return new Match(end, DURATION_REPLACEMENT);
        }

        private static int matchDurationUnit(ScanCursor cursor, int offset) {
            if (cursor.startsWithIgnoreCase("milliseconds", offset)) {
                return 12;
            }
            if (cursor.startsWithIgnoreCase("seconds", offset)) {
                return 7;
            }
            if (cursor.startsWithIgnoreCase("ms", offset)) {
                return 2;
            }
            if (cursor.startsWithIgnoreCase("s", offset)) {
                return 1;
            }
            return 0;
        }
    }

    private static final class LineColumnRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            Match bracketMatch = tryMatchBracket(cursor);
            if (bracketMatch != null) {
                return bracketMatch;
            }
            Match wordMatch = tryMatchWord(cursor);
            if (wordMatch != null) {
                return wordMatch;
            }
            return tryMatchColon(cursor);
        }

        private Match tryMatchBracket(ScanCursor cursor) {
            int start = cursor.offset();
            int idx = start;
            if (cursor.startsWith(":[", idx)) {
                idx += 2;
            } else if (cursor.hasChar(idx, '[')) {
                idx += 1;
            } else {
                return null;
            }
            int lineEnd = cursor.readDigits(idx);
            if (lineEnd == idx || !cursor.hasChar(lineEnd, ',')) {
                return null;
            }
            idx = cursor.skipWhitespace(lineEnd + 1);
            int colEnd = cursor.readDigits(idx);
            if (colEnd == idx || !cursor.hasChar(colEnd, ']')) {
                return null;
            }
            return new Match(colEnd + 1, LINE_COL_REPLACEMENT);
        }

        private Match tryMatchWord(ScanCursor cursor) {
            int start = cursor.offset();
            if (start > 0 && !cursor.isTokenBoundary(start - 1)) {
                return null;
            }
            if (!cursor.startsWithIgnoreCase("line", start)) {
                return null;
            }
            int idx = start + 4;
            if (!cursor.isWhitespace(idx)) {
                return null;
            }
            idx = cursor.skipWhitespace(idx);
            int lineEnd = cursor.readDigits(idx);
            if (lineEnd == idx) {
                return null;
            }
            int end = lineEnd;
            int check = lineEnd;
            if (cursor.hasChar(check, ',')) {
                check++;
            }
            check = cursor.skipWhitespace(check);
            int colWordLen = 0;
            if (cursor.startsWithIgnoreCase("column", check)) {
                colWordLen = 6;
            } else if (cursor.startsWithIgnoreCase("col", check)) {
                colWordLen = 3;
            }
            if (colWordLen > 0) {
                check = cursor.skipWhitespace(check + colWordLen);
                int colEnd = cursor.readDigits(check);
                if (colEnd > check) {
                    end = colEnd;
                }
            }
            if (!cursor.isTokenBoundary(end)) {
                return null;
            }
            return new Match(end, LINE_COL_REPLACEMENT);
        }

        private Match tryMatchColon(ScanCursor cursor) {
            int start = cursor.offset();
            if (cursor.current() != ':') {
                return null;
            }
            boolean afterContext = cursor.outputEndsWith(PATH_REPLACEMENT) || cursor.hasSourceExtensionBefore(start);
            if (!afterContext) {
                return null;
            }
            int idx = cursor.readDigits(start + 1);
            if (idx == start + 1) {
                return null;
            }
            if (cursor.hasChar(idx, ':') && cursor.isDigit(idx + 1)) {
                idx = cursor.readDigits(idx + 2);
            }
            if (!cursor.isTokenBoundary(idx)) {
                return null;
            }
            return new Match(idx, LINE_COL_REPLACEMENT);
        }
    }

    private static final class PathRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            Match winPath = tryMatchWindowsPath(cursor);
            if (winPath != null) {
                return winPath;
            }
            return tryMatchLinuxPath(cursor);
        }

        private Match tryMatchWindowsPath(ScanCursor cursor) {
            int start = cursor.offset();
            if (!cursor.hasRemaining(4)) {
                return null;
            }
            char drive = cursor.charAt(start);
            if (!Character.isLetter(drive) || cursor.charAt(start + 1) != ':' || cursor.charAt(start + 2) != '\\') {
                return null;
            }
            int idx = start + 3;
            int lastSlash = start + 2;
            int lastDot = -1;
            while (idx < cursor.length()) {
                if (cursor.isPathTerminator(idx)) {
                    break;
                }
                char c = cursor.charAt(idx);
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
            return new Match(idx, PATH_REPLACEMENT);
        }

        private Match tryMatchLinuxPath(ScanCursor cursor) {
            int start = cursor.offset();
            if (cursor.current() != '/') {
                return null;
            }
            if (start > 0) {
                char prev = cursor.charAt(start - 1);
                if (!Character.isWhitespace(prev) && prev != '[' && prev != '(' && prev != '\'' && prev != '"' && prev != ':') {
                    return null;
                }
            }
            int idx = start;
            int lastSlash = start;
            int lastDot = -1;
            int slashCount = 0;

            while (idx < cursor.length()) {
                if (cursor.isPathTerminator(idx)) {
                    break;
                }
                char c = cursor.charAt(idx);
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
            return new Match(idx, PATH_REPLACEMENT);
        }

        private static boolean isPathChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
        }
    }
}
