package guru.thomasweber.devagent.cognitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorSignatureExtractorTest {

    private final ErrorSignatureExtractor extractor = new ErrorSignatureExtractor();

    @Test
    @DisplayName("Given null or blank input - when extracting - then returns empty text and fallback signature")
    void givenNullOrBlankInput_whenExtracting_thenHandlesGracefully() {
        assertThat(extractor.extractNormalizedText(null)).isEmpty();
        assertThat(extractor.extractNormalizedText("")).isEmpty();
        assertThat(extractor.extractNormalizedText("   \t\n  ")).isEmpty();

        String sigNull = extractor.extractSignature(null);
        String sigEmpty = extractor.extractSignature("");
        String sigBlank = extractor.extractSignature("   \t\n  ");

        assertThat(sigNull)
            .hasSize(64)
            .matches("^[0-9a-f]{64}$")
            .isEqualTo(sigEmpty)
            .isEqualTo(sigBlank);
    }

    @Test
    @DisplayName("Given error traces with different absolute paths and line numbers - when extracting - then produces identical signatures")
    void givenDifferentPathsAndLineNumbers_whenExtracting_thenProducesIdenticalSignatures() {
        String trace1 = """
            [ERROR] /home/developer/projects/dev-agent/src/main/java/App.java:[42,15] cannot find symbol
              symbol:   class Foo
              location: class App
            """;

        String trace2 = """
            [ERROR] /tmp/ci-build-8472/src/main/java/App.java:[108,22] cannot find symbol
              symbol:   class Foo
              location: class App
            """;

        String sig1 = extractor.extractSignature(trace1);
        String sig2 = extractor.extractSignature(trace2);

        assertThat(sig1)
            .hasSize(64)
            .matches("^[0-9a-f]{64}$")
            .isEqualTo(sig2);

        // Windows path normalization
        String winTrace = "[ERROR] C:\\Users\\developer\\projects\\dev-agent\\src\\main\\java\\App.java:[42,15] cannot find symbol\n  symbol:   class Foo\n  location: class App";
        String winSig = extractor.extractSignature(winTrace);
        assertThat(winSig).isEqualTo(sig1);

        // Raw colon line/col without brackets (tsc, gcc, standard javac)
        String rawColon1 = "/home/developer/projects/dev-agent/src/main/java/App.java:42:15: error: cannot find symbol Foo";
        String rawColon2 = "/tmp/ci-agent-worktree/src/main/java/App.java:108:30: error: cannot find symbol Foo";
        assertThat(extractor.extractSignature(rawColon1)).isEqualTo(extractor.extractSignature(rawColon2));

        // Path boundary checks
        assertThat(extractor.extractNormalizedText("In file C:\\projects\\dev-agent\\build without ext"))
            .isEqualTo("In file C:\\projects\\dev-agent\\build without ext");
        assertThat(extractor.extractNormalizedText("In file 1:\\projects\\App.java"))
            .isEqualTo("In file 1:\\projects\\App.java");
        assertThat(extractor.extractNormalizedText("In file /tmp/ci-build/Makefile without ext"))
            .isEqualTo("In file /tmp/ci-build/Makefile without ext");
        assertThat(extractor.extractNormalizedText("Embeddedabc/def/App.java"))
            .isEqualTo("Embeddedabc/def/App.java");
    }

    @Test
    @DisplayName("Given memory addresses - when extracting - then masks addresses while preserving email addresses")
    void givenMemoryAddresses_whenExtracting_thenMasksAddressesWhilePreservingEmails() {
        String trace1 = "NullPointerException at guru.thomasweber.Service@7e8a91bc";
        String trace2 = "NullPointerException at guru.thomasweber.Service@3d4f5a6b";

        String norm1 = extractor.extractNormalizedText(trace1);
        String norm2 = extractor.extractNormalizedText(trace2);

        assertThat(norm1)
            .isEqualTo("NullPointerException at guru.thomasweber.Service@<ADDR>")
            .isEqualTo(norm2);

        assertThat(extractor.extractSignature(trace1)).isEqualTo(extractor.extractSignature(trace2));

        // Verify email addresses are not masked as memory addresses
        String emailTrace = "Notification sent to user@domain.com successfully";
        assertThat(extractor.extractNormalizedText(emailTrace))
            .contains("user@domain.com")
            .doesNotContain("@<ADDR>");

        String hexEmailTrace = "Notification sent to user@abcdef12.org successfully";
        assertThat(extractor.extractNormalizedText(hexEmailTrace))
            .contains("user@abcdef12.org")
            .doesNotContain("@<ADDR>");

        // Short hex (< 6 chars) or long hex (> 16 chars) or followed by letter
        assertThat(extractor.extractNormalizedText("Address is @12345"))
            .isEqualTo("Address is @12345");
        assertThat(extractor.extractNormalizedText("Address is @1234567890abcdef123"))
            .isEqualTo("Address is @1234567890abcdef123");
        assertThat(extractor.extractNormalizedText("Handle is @deadbeefz in forum"))
            .isEqualTo("Handle is @deadbeefz in forum");
        assertThat(extractor.extractNormalizedText("Object is Service@7e8a91bc."))
            .isEqualTo("Object is Service@<ADDR>.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[2026-11-01T21:49:00.123Z] [ERROR] Compilation failed",
        "[2026-11-01 21:49:00] [ERROR] Compilation failed",
        "[2026-11-01T21:49:00+02:00] [ERROR] Compilation failed"
    })
    @DisplayName("Given ISO timestamps - when extracting - then masks timestamps to <TIME>")
    void givenIsoTimestamps_whenExtracting_thenMasksTimestamps(String trace) {
        String normalized = extractor.extractNormalizedText(trace);
        assertThat(normalized).isEqualTo("[<TIME>] [ERROR] Compilation failed");
    }

    @Test
    @DisplayName("Given clock times - when extracting - then masks time components to <TIME>")
    void givenClockTimes_whenExtracting_thenMasksClockTimes() {
        String trace1 = "Build failed at 21:49:00 in step compile";
        String trace2 = "Build failed at 14:15:30.456 in step compile";

        assertThat(extractor.extractNormalizedText(trace1))
            .isEqualTo("Build failed at <TIME> in step compile");
        assertThat(extractor.extractNormalizedText(trace2))
            .isEqualTo("Build failed at <TIME> in step compile");

        // Edge cases: clock time preceded by letter or followed by alphanumeric
        assertThat(extractor.extractNormalizedText("prefix21:49:00 in log"))
            .isEqualTo("prefix21:49:00 in log");
        assertThat(extractor.extractNormalizedText("at 21:49:00px in log"))
            .isEqualTo("at 21:49:00px in log");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Execution took 1250ms total",
        "Execution took 1250 ms total",
        "Execution took 850 milliseconds total",
        "Execution took 12.5 s total",
        "Execution took 5 seconds total",
        "Execution took 100\tms total"
    })
    @DisplayName("Given duration variations - when extracting - then masks durations to <DURATION>")
    void givenDurations_whenExtracting_thenMasksDurations(String trace) {
        String normalized = extractor.extractNormalizedText(trace);
        assertThat(normalized).isEqualTo("Execution took <DURATION> total");
    }

    @Test
    @DisplayName("Given invalid duration patterns - when extracting - then leaves unchanged")
    void givenInvalidDurations_whenExtracting_thenPreservesTokens() {
        assertThat(extractor.extractNormalizedText("Version v1.5s released"))
            .isEqualTo("Version v1.5s released");
        assertThat(extractor.extractNormalizedText("Process 5static running"))
            .isEqualTo("Process 5static running");
        assertThat(extractor.extractNormalizedText("Sent 100msg received"))
            .isEqualTo("Sent 100msg received");
    }

    @Test
    @DisplayName("Given line and column word formats - when extracting - then masks coordinates")
    void givenLineColumnWordFormats_whenExtracting_thenMasksCoordinates() {
        String trace1 = "Syntax error in App.java at line 42, col 15";
        String trace2 = "Syntax error in App.java at line 42 column 15";
        String trace3 = "Syntax error in App.java at line 42";

        assertThat(extractor.extractNormalizedText(trace1))
            .isEqualTo("Syntax error in App.java at :<LINE>:<COL>");
        assertThat(extractor.extractNormalizedText(trace2))
            .isEqualTo("Syntax error in App.java at :<LINE>:<COL>");
        assertThat(extractor.extractNormalizedText(trace3))
            .isEqualTo("Syntax error in App.java at :<LINE>:<COL>");

        String absTrace = "Syntax error in /workspace/App.java at line 42, col 15";
        assertThat(extractor.extractNormalizedText(absTrace))
            .isEqualTo("Syntax error in <PATH> at :<LINE>:<COL>");

        // Non-matching line formats
        assertThat(extractor.extractNormalizedText("headline 42 was read"))
            .isEqualTo("headline 42 was read");
        assertThat(extractor.extractNormalizedText("at line abc error"))
            .isEqualTo("at line abc error");
        assertThat(extractor.extractNormalizedText("at line 42px error"))
            .isEqualTo("at line 42px error");
    }

    @Test
    @DisplayName("Given raw colon coordinate on source file - when extracting - then masks coordinates")
    void givenRawColonCoordinate_whenExtracting_thenMasksCoordinate() {
        String traceSingle = "App.java:42: error: cannot find symbol";
        assertThat(extractor.extractNormalizedText(traceSingle))
            .isEqualTo("App.java:<LINE>:<COL>: error: cannot find symbol");

        String traceDouble = "App.java:42:15: error: cannot find symbol";
        assertThat(extractor.extractNormalizedText(traceDouble))
            .isEqualTo("App.java:<LINE>:<COL>: error: cannot find symbol");

        String absTrace = "/src/main/java/App.java:42:15: error: cannot find symbol";
        assertThat(extractor.extractNormalizedText(absTrace))
            .isEqualTo("<PATH>:<LINE>:<COL>: error: cannot find symbol");

        // Non-matching colon coordinate
        assertThat(extractor.extractNormalizedText("App.java:: error"))
            .isEqualTo("App.java:: error");
        assertThat(extractor.extractNormalizedText("App.java:42a: error"))
            .isEqualTo("App.java:42a: error");
    }

    @Test
    @DisplayName("Given bracket coordinates - when extracting - then masks bracket coordinates")
    void givenBracketCoordinates_whenExtracting_thenMasksCoordinates() {
        String trace1 = "App.java:[42, 15] error";
        String trace2 = "App.java[42,15] error";

        assertThat(extractor.extractNormalizedText(trace1))
            .isEqualTo("App.java:<LINE>:<COL> error");
        assertThat(extractor.extractNormalizedText(trace2))
            .isEqualTo("App.java:<LINE>:<COL> error");

        String absTrace = "/path/to/App.java:[42, 15] error";
        assertThat(extractor.extractNormalizedText(absTrace))
            .isEqualTo("<PATH>:<LINE>:<COL> error");

        // Malformed bracket coordinates
        assertThat(extractor.extractNormalizedText("App.java:[42; 15] error"))
            .isEqualTo("App.java:[42; 15] error");
        assertThat(extractor.extractNormalizedText("App.java:[42,] error"))
            .isEqualTo("App.java:[42,] error");
        assertThat(extractor.extractNormalizedText("App.java:[abc, 15] error"))
            .isEqualTo("App.java:[abc, 15] error");
        assertThat(extractor.extractNormalizedText("App.java:[42, 15 error"))
            .isEqualTo("App.java:[42, 15 error");

        // Ordinary bracket values without source location are left unchanged
        assertThat(extractor.extractNormalizedText("expected: [1, 2] but was: [1, 3]"))
            .isEqualTo("expected: [1, 2] but was: [1, 3]");
    }

    @Test
    @DisplayName("Given network URL with port number - when extracting - then does not erroneously mask port as line coordinate")
    void givenUrlWithPort_whenExtracting_thenPreservesPortWithoutMaskingAsLineCol() {
        String trace = "Connection refused to http://localhost:8080/api/v1/resource";
        String normalized = extractor.extractNormalizedText(trace);
        assertThat(normalized)
            .contains("localhost:8080")
            .doesNotContain("<LINE>");
    }

    @Test
    @DisplayName("Given traces with exit codes - when extracting - then preserves exit code numbers and produces distinct signatures")
    void givenTracesWithExitCodes_whenExtracting_thenPreservesExitCodesAndProducesDistinctSignatures() {
        String trace1 = "Process finished with exit code: 1";
        String trace2 = "Process finished with exit code: 2";

        String norm1 = extractor.extractNormalizedText(trace1);
        String norm2 = extractor.extractNormalizedText(trace2);

        assertThat(norm1).isEqualTo("Process finished with exit code: 1");
        assertThat(norm2).isEqualTo("Process finished with exit code: 2");

        String sig1 = extractor.extractSignature(trace1);
        String sig2 = extractor.extractSignature(trace2);
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("Given whitespace variations - when extracting - then collapses to single space and trims output")
    void givenWhitespaceVariations_whenExtracting_thenCollapsesWhitespaceAndTrims() {
        String trace = "  \n\t  [ERROR]    \n  Failed to compile   \n\t  class Bar   \n  ";
        String normalized = extractor.extractNormalizedText(trace);
        assertThat(normalized).isEqualTo("[ERROR] Failed to compile class Bar");
    }

    @Test
    @DisplayName("Given different root errors - when extracting - then produces distinct signatures")
    void givenDifferentErrors_whenExtracting_thenProducesDistinctSignatures() {
        String trace1 = "cannot find symbol: class Alpha";
        String trace2 = "cannot find symbol: class Beta";

        String sig1 = extractor.extractSignature(trace1);
        String sig2 = extractor.extractSignature(trace2);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("Given massive error trace exceeding max chars - when extracting - then truncates safely and extracts signature")
    void givenMassiveErrorTrace_whenExtracting_thenTruncatesSafely() {
        String massive = "massive error trace line ".repeat(3000);
        String sig = extractor.extractSignature(massive);
        assertThat(sig).hasSize(64);

        String normalized = extractor.extractNormalizedText(massive);
        assertThat(normalized.length()).isLessThanOrEqualTo(ErrorSignatureExtractor.MAX_INPUT_CHARS);
    }
}
