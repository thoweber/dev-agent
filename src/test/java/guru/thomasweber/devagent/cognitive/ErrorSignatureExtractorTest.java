package guru.thomasweber.devagent.cognitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorSignatureExtractorTest {

    private final ErrorSignatureExtractor extractor = new ErrorSignatureExtractor();

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

        assertThat(sig1).hasSize(64);
        assertThat(sig1).matches("^[0-9a-f]{64}$");
        assertThat(sig1).isEqualTo(sig2);

        // Windows path normalization
        String winTrace = "[ERROR] C:\\Users\\developer\\projects\\dev-agent\\src\\main\\java\\App.java:[42,15] cannot find symbol\n  symbol:   class Foo\n  location: class App";
        String winSig = extractor.extractSignature(winTrace);
        assertThat(winSig).isEqualTo(sig1);
    }

    @Test
    @DisplayName("Given error traces with timestamps and elapsed duration - when extracting - then masks time components")
    void givenTimestampsAndDuration_whenExtracting_thenMasksTimeComponents() {
        String trace1 = "[2026-11-01T21:49:00.123Z] [ERROR] Compilation failed in 1250ms at 21:49:00: ClassNotFoundException: Bar";
        String trace2 = "[2026-11-01T22:15:30.999Z] [ERROR] Compilation failed in 850ms at 22:15:30: ClassNotFoundException: Bar";

        String sig1 = extractor.extractSignature(trace1);
        String sig2 = extractor.extractSignature(trace2);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("Given error traces with memory addresses - when extracting - then masks object addresses")
    void givenMemoryAddresses_whenExtracting_thenMasksAddresses() {
        String trace1 = "NullPointerException at guru.thomasweber.Service@7e8a91bc";
        String trace2 = "NullPointerException at guru.thomasweber.Service@3d4f5a6b";

        String sig1 = extractor.extractSignature(trace1);
        String sig2 = extractor.extractSignature(trace2);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("Given null or blank trace - when extracting - then returns fallback signature without throwing")
    void givenNullOrBlankTrace_whenExtracting_thenReturnsFallback() {
        String sigNull = extractor.extractSignature(null);
        String sigBlank = extractor.extractSignature("   ");

        assertThat(sigNull).hasSize(64);
        assertThat(sigBlank).hasSize(64);
        assertThat(sigNull).isEqualTo(sigBlank);
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
    @DisplayName("Given network URL with port number - when extracting - then does not erroneously mask port as line coordinate")
    void givenUrlWithPort_whenExtracting_thenPreservesPortWithoutMaskingAsLineCol() {
        String trace = "Connection refused to http://localhost:8080/api/v1/resource";
        String normalized = extractor.extractNormalizedText(trace);
        assertThat(normalized).contains("localhost:8080");
        assertThat(normalized).doesNotContain("<LINE>");
    }

    @Test
    @DisplayName("Given massive error trace exceeding max chars - when extracting - then truncates safely and extracts signature")
    void givenMassiveErrorTrace_whenExtracting_thenTruncatesSafely() {
        String massive = "massive error trace line ".repeat(3000);
        String sig = extractor.extractSignature(massive);
        assertThat(sig).hasSize(64);
    }

    @Test
    @DisplayName("Given traces with exit codes - when extracting - then preserves exit code numbers and produces distinct signatures")
    void givenTracesWithExitCodes_whenExtracting_thenPreservesExitCodesAndProducesDistinctSignatures() {
        String trace1 = "Process finished with exit code: 1";
        String trace2 = "Process finished with exit code: 2";

        String norm1 = extractor.extractNormalizedText(trace1);
        String norm2 = extractor.extractNormalizedText(trace2);

        assertThat(norm1).contains("exit code: 1");
        assertThat(norm2).contains("exit code: 2");

        String sig1 = extractor.extractSignature(trace1);
        String sig2 = extractor.extractSignature(trace2);
        assertThat(sig1).isNotEqualTo(sig2);
    }
}
