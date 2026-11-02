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

                Match match = matchFirstRule(cursor);

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

        private Match matchFirstRule(ScanCursor cursor) {
            for (NormalizationRule rule : rules) {
                Match match = rule.tryMatch(cursor);
                if (match != null) {
                    return match;
                }
            }
            return null;
        }
    }

    private static final class MemoryAddressRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            if (cursor.current() != '@') {
                return null;
            }
            var probe = cursor.fork();
            probe.skip(1);
            int hexCount = probe.skipHexDigits();
            if (hexCount < 6 || hexCount > 16) {
                return null;
            }
            if (!probe.isAtTokenBoundary() || probe.isEmailDomain()) {
                return null;
            }
            return probe.match(ADDR_REPLACEMENT);
        }
    }

    private static final class TimestampRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            var probe = cursor.fork();
            if (!probe.consumePattern("####-##-## ##:##:##")) {
                return null;
            }
            if (probe.consumeIf('.')) {
                probe.skipDigits();
            }
            if (probe.consumeIf('Z')) {
                // UTC Zulu
            } else if (probe.consumeIf('+') || probe.consumeIf('-')) {
                probe.skipWhileDigitsOrColon();
            }
            if (!probe.isAtTokenBoundary()) {
                return null;
            }
            return probe.match(TIME_REPLACEMENT);
        }
    }

    private static final class ClockTimeRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            if (cursor.hasPrecedingWordChar()) {
                return null;
            }
            var probe = cursor.fork();
            if (!probe.consumePattern("##:##:##")) {
                return null;
            }
            if (probe.consumeIf('.')) {
                probe.skipDigits();
            }
            if (!probe.isAtTokenBoundary()) {
                return null;
            }
            return probe.match(TIME_REPLACEMENT);
        }
    }

    private static final class DurationRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            if (cursor.hasPrecedingWordCharOrDot() || !cursor.isDigit()) {
                return null;
            }
            var probe = cursor.fork();
            probe.skipDecimalNumber();
            probe.skipInlineWhitespace();
            if (!probe.consumeAnyIgnoreCase("milliseconds", "seconds", "ms", "s")) {
                return null;
            }
            if (!probe.isAtTokenBoundary()) {
                return null;
            }
            return probe.match(DURATION_REPLACEMENT);
        }
    }

    private static final class LineColumnRule implements NormalizationRule {
        @Override
        public Match tryMatch(ScanCursor cursor) {
            Match bracket = tryMatchBracket(cursor);
            if (bracket != null) {
                return bracket;
            }
            Match word = tryMatchWord(cursor);
            if (word != null) {
                return word;
            }
            return tryMatchColon(cursor);
        }

        private Match tryMatchBracket(ScanCursor cursor) {
            var probe = cursor.fork();
            if (!probe.consumeIf(":[") && !probe.consumeIf('[')) {
                return null;
            }
            if (probe.skipDigits() == 0 || !probe.consumeIf(',')) {
                return null;
            }
            probe.skipWhitespace();
            if (probe.skipDigits() == 0 || !probe.consumeIf(']')) {
                return null;
            }
            return probe.match(LINE_COL_REPLACEMENT);
        }

        private Match tryMatchWord(ScanCursor cursor) {
            if (cursor.hasPrecedingWordChar()) {
                return null;
            }
            var probe = cursor.fork();
            if (!probe.consumeIfIgnoreCase("line")) {
                return null;
            }
            if (probe.skipWhitespace() == 0 || probe.skipDigits() == 0) {
                return null;
            }
            var colProbe = probe.fork();
            colProbe.consumeIf(',');
            colProbe.skipWhitespace();
            if (colProbe.consumeAnyIgnoreCase("column", "col")) {
                colProbe.skipWhitespace();
                if (colProbe.skipDigits() > 0) {
                    probe = colProbe;
                }
            }
            if (!probe.isAtTokenBoundary()) {
                return null;
            }
            return probe.match(LINE_COL_REPLACEMENT);
        }

        private Match tryMatchColon(ScanCursor cursor) {
            if (cursor.current() != ':') {
                return null;
            }
            if (!cursor.outputEndsWith(PATH_REPLACEMENT) && !cursor.hasPrecedingSourceExtension()) {
                return null;
            }
            var probe = cursor.fork();
            probe.skip(1);
            if (probe.skipDigits() == 0) {
                return null;
            }
            var colProbe = probe.fork();
            if (colProbe.consumeIf(':') && colProbe.skipDigits() > 0) {
                probe = colProbe;
            }
            if (!probe.isAtTokenBoundary()) {
                return null;
            }
            return probe.match(LINE_COL_REPLACEMENT);
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
            var probe = cursor.fork();
            if (!probe.consumeWindowsDrive()) {
                return null;
            }
            if (!probe.consumePathWithExtension('\\')) {
                return null;
            }
            return probe.match(PATH_REPLACEMENT);
        }

        private Match tryMatchLinuxPath(ScanCursor cursor) {
            if (cursor.current() != '/' || !cursor.hasPrecedingLinuxPathBoundary()) {
                return null;
            }
            var probe = cursor.fork();
            if (!probe.consumePathWithExtension('/')) {
                return null;
            }
            return probe.match(PATH_REPLACEMENT);
        }
    }

    private static final class ScanCursor {
        private final String input;
        private final StringBuilder output;
        private int offset;

        private ScanCursor(String input, StringBuilder output) {
            this(input, output, 0);
        }

        private ScanCursor(String input, StringBuilder output, int offset) {
            this.input = input;
            this.output = output;
            this.offset = offset;
        }

        public ScanCursor fork() {
            return new ScanCursor(input, output, offset);
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

        public char current() {
            return input.charAt(offset);
        }

        public Match match(String replacement) {
            return new Match(offset, replacement);
        }

        public boolean isDigit() {
            return offset < input.length() && Character.isDigit(input.charAt(offset));
        }

        public boolean isAtTokenBoundary() {
            return offset >= input.length() || !Character.isLetterOrDigit(input.charAt(offset));
        }

        public boolean hasPrecedingWordChar() {
            return offset > 0 && Character.isLetterOrDigit(input.charAt(offset - 1));
        }

        public boolean hasPrecedingWordCharOrDot() {
            if (offset <= 0) {
                return false;
            }
            char prev = input.charAt(offset - 1);
            return Character.isLetterOrDigit(prev) || prev == '.';
        }

        public boolean hasPrecedingLinuxPathBoundary() {
            if (offset <= 0) {
                return true;
            }
            char prev = input.charAt(offset - 1);
            return Character.isWhitespace(prev) || prev == '[' || prev == '(' || prev == '\'' || prev == '"' || prev == ':';
        }

        public void skip(int count) {
            this.offset += count;
        }

        public int skipDigits() {
            int start = offset;
            while (offset < input.length() && Character.isDigit(input.charAt(offset))) {
                offset++;
            }
            return offset - start;
        }

        public int skipHexDigits() {
            int start = offset;
            while (offset < input.length() && isHexDigit(input.charAt(offset))) {
                offset++;
            }
            return offset - start;
        }

        public void skipDecimalNumber() {
            skipDigits();
            if (offset + 1 < input.length() && input.charAt(offset) == '.' && Character.isDigit(input.charAt(offset + 1))) {
                offset += 2;
                skipDigits();
            }
        }

        public int skipWhitespace() {
            int start = offset;
            while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) {
                offset++;
            }
            return offset - start;
        }

        public void skipInlineWhitespace() {
            while (offset < input.length() && (input.charAt(offset) == ' ' || input.charAt(offset) == '\t')) {
                offset++;
            }
        }

        public void skipWhileDigitsOrColon() {
            while (offset < input.length() && (Character.isDigit(input.charAt(offset)) || input.charAt(offset) == ':')) {
                offset++;
            }
        }

        public boolean consumeIf(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        public boolean consumeIf(String expected) {
            if (input.startsWith(expected, offset)) {
                offset += expected.length();
                return true;
            }
            return false;
        }

        public boolean consumeIfIgnoreCase(String expected) {
            if (startsWithIgnoreCase(expected)) {
                offset += expected.length();
                return true;
            }
            return false;
        }

        public boolean consumeAnyIgnoreCase(String... candidates) {
            for (String candidate : candidates) {
                if (consumeIfIgnoreCase(candidate)) {
                    return true;
                }
            }
            return false;
        }

        public boolean consumePattern(String pattern) {
            if (offset + pattern.length() > input.length()) {
                return false;
            }
            for (int k = 0; k < pattern.length(); k++) {
                char p = pattern.charAt(k);
                char c = input.charAt(offset + k);
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
            offset += pattern.length();
            return true;
        }

        public boolean consumeWindowsDrive() {
            if (offset + 3 <= input.length()
                && Character.isLetter(input.charAt(offset))
                && input.charAt(offset + 1) == ':'
                && input.charAt(offset + 2) == '\\') {
                offset += 3;
                return true;
            }
            return false;
        }

        public boolean consumePathWithExtension(char separator) {
            int lastSlash = (offset > 0 && input.charAt(offset - 1) == separator) ? offset - 1 : -1;
            int lastDot = -1;
            int slashCount = (lastSlash >= 0) ? 1 : 0;

            while (offset < input.length() && !isPathTerminator()) {
                char c = input.charAt(offset);
                if (c == separator) {
                    lastSlash = offset;
                    slashCount++;
                } else if (c == '.') {
                    lastDot = offset;
                } else if (!isPathChar(c)) {
                    break;
                }
                offset++;
            }

            return slashCount >= 1 && lastDot > lastSlash && lastDot + 1 < offset;
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

        public boolean hasPrecedingSourceExtension() {
            int k = offset - 1;
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

        public boolean isEmailDomain() {
            if (offset >= input.length() || input.charAt(offset) != '.') {
                return false;
            }
            int k = offset + 1;
            while (k < input.length() && Character.isLetter(input.charAt(k))) {
                k++;
            }
            int tldLen = k - (offset + 1);
            return tldLen >= 2 && tldLen <= 10 && (k >= input.length() || !Character.isLetterOrDigit(input.charAt(k)));
        }

        private boolean isPathTerminator() {
            if (offset >= input.length()) {
                return true;
            }
            char c = input.charAt(offset);
            if (Character.isWhitespace(c) || c == '[') {
                return true;
            }
            if (c == ':' && offset + 1 < input.length()) {
                char next = input.charAt(offset + 1);
                return next == '[' || Character.isDigit(next);
            }
            return false;
        }

        private boolean startsWithIgnoreCase(String prefix) {
            if (offset + prefix.length() > input.length()) {
                return false;
            }
            for (int k = 0; k < prefix.length(); k++) {
                char c1 = Character.toLowerCase(input.charAt(offset + k));
                char c2 = Character.toLowerCase(prefix.charAt(k));
                if (c1 != c2) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private static boolean isPathChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
        }
    }
}
