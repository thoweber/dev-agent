package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.testutil.ArchitecturalRecordMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatekeeperCacheKeyGeneratorTest {

    private final GatekeeperCacheKeyGenerator generator = new GatekeeperCacheKeyGenerator();

    @Test
    @DisplayName("Given targetContent and retrievedAdrs - when generating cache key - then produces deterministic 64-char hex SHA-256")
    void givenInputs_whenGenerating_thenProducesDeterministicSha256() throws NoSuchMethodException {
        Method method = Object.class.getMethod("toString");
        String diff = "--- a/App.java\n+++ b/App.java\n@@ -1,1 +1,2 @@\n+import java.util.List;";
        String adrs = "ADR-001: Quarkus Picocli Native CLI\nADR-005: Pure-Java BM25";

        Object key1 = generator.generate(method, diff, adrs);
        Object key2 = generator.generate(method, diff, adrs);

        assertThat(key1).isInstanceOf(String.class);
        String hex1 = (String) key1;
        String hex2 = (String) key2;

        assertThat(hex1)
            .hasSize(64)
            .matches("^[0-9a-f]{64}$")
            .isEqualTo(hex2);
    }

    @Test
    @DisplayName("Given whitespace variations - when generating cache key - then normalizes whitespace deterministically")
    void givenWhitespaceVariations_whenGenerating_thenNormalizes() throws NoSuchMethodException {
        Method method = Object.class.getMethod("toString");
        String diff1 = "diff --git a/file b/file\n+code";
        String diff2 = "   diff --git a/file b/file\n+code\n\n  ";
        String adrs = "ADR-001";

        Object key1 = generator.generate(method, diff1, adrs);
        Object key2 = generator.generate(method, diff2, adrs);

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("Given different diff or adrs - when generating cache key - then produces distinct keys")
    void givenDifferentInputs_whenGenerating_thenProducesDistinctKeys() throws NoSuchMethodException {
        Method method = Object.class.getMethod("toString");
        String diff1 = "+line 1";
        String diff2 = "+line 2";
        String adrs = "ADR-001";

        Object key1 = generator.generate(method, diff1, adrs);
        Object key2 = generator.generate(method, diff2, adrs);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("Given null or missing parameters - when generating cache key - then handles gracefully without throwing")
    void givenNullOrMissingParams_whenGenerating_thenHandlesGracefully() throws NoSuchMethodException {
        Method method = Object.class.getMethod("toString");

        Object keyNull = generator.generate(method, (Object[]) null);
        assertThat(keyNull).isNotNull();
        assertThat(((String) keyNull)).hasSize(64);

        Object keyEmpty = generator.generate(method);
        assertThat(keyEmpty).isNotNull();
        assertThat(((String) keyEmpty)).hasSize(64);

        Object keyWithNullElement = generator.generate(method, null, "adrs");
        assertThat(keyWithNullElement).isNotNull();
        assertThat(((String) keyWithNullElement)).hasSize(64);

        Object keyNullFirstParam = generator.generate(method, new Object[]{null});
        assertThat(keyNullFirstParam).isNotNull();
        assertThat(((String) keyNullFirstParam)).hasSize(64);

        Object keyZeroLengthArray = generator.generate(method, new Object[0]);
        assertThat(keyZeroLengthArray).isNotNull();
        assertThat(((String) keyZeroLengthArray)).hasSize(64);

        Object keySingleParam = generator.generate(method, "only-diff");
        assertThat(keySingleParam).isNotNull();
        assertThat(((String) keySingleParam)).hasSize(64);

        Object keyNullSecondParam = generator.generate(method, "only-diff", null);
        assertThat(keyNullSecondParam).isNotNull();
        assertThat(((String) keyNullSecondParam)).hasSize(64);
    }

    @Test
    @DisplayName("Given list of ArchitecturalRecord in different orders - when generating cache key - then produces identical deterministic key")
    void givenListOfRecordsInDifferentOrder_whenGenerating_thenProducesIdenticalKey() throws NoSuchMethodException {
        Method method = Object.class.getMethod("toString");
        String diff = "+line 1";
        ArchitecturalRecord rec1 = ArchitecturalRecordMother.adr001();
        ArchitecturalRecord rec2 = ArchitecturalRecordMother.adr005();

        Object key1 = generator.generate(method, diff, List.of(rec1, rec2));
        Object key2 = generator.generate(method, diff, List.of(rec2, rec1));

        assertThat(key1).isEqualTo(key2);

        // Record with same ID but different summary produces different cache key
        ArchitecturalRecord rec1Modified = rec1.toBuilder().summary("Updated summary content").build();
        Object keyModified = generator.generate(method, diff, List.of(rec1Modified, rec2));
        assertThat(keyModified).isNotEqualTo(key1);

        // Also test with list containing strings or nulls
        List<Object> mixed1 = new ArrayList<>();
        mixed1.add("ADR-005");
        mixed1.add("ADR-001");
        mixed1.add(null);
        List<Object> mixed2 = new ArrayList<>();
        mixed2.add(null);
        mixed2.add("ADR-001");
        mixed2.add("ADR-005");
        Object keyMixed1 = generator.generate(method, diff, mixed1);
        Object keyMixed2 = generator.generate(method, diff, mixed2);
        assertThat(keyMixed1).isEqualTo(keyMixed2);

        // Record with null summary and null content
        ArchitecturalRecord recNullSummaryContent = ArchitecturalRecord.builder()
            .id("ADR-999")
            .title("Title")
            .status("ACCEPTED")
            .build();
        Object keyNullSummaryContent = generator.generate(method, diff, List.of(recNullSummaryContent));
        assertThat(keyNullSummaryContent).isNotNull();
    }

    @Test
    @DisplayName("Given inputs with embedded delimiter characters - when generating cache key - then length-framing avoids collisions")
    void givenInputsWithDelimiters_whenGenerating_thenAvoidsCollisions() throws NoSuchMethodException {
        Method method = Object.class.getMethod("toString");
        Object key1 = generator.generate(method, "x\nADRS:1:y", "z");
        Object key2 = generator.generate(method, "x", "y\nADRS:1:z");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("Given records with different field boundaries - when generating cache key - then length-framing produces distinct keys")
    void givenRecordsWithDifferentFieldBoundaries_whenGenerating_thenProducesDistinctKeys() throws NoSuchMethodException {
        Method method = Object.class.getMethod("toString");
        String diff = "+line 1";

        ArchitecturalRecord rec1 = ArchitecturalRecord.builder()
            .id("ADR-001")
            .title("Title")
            .status("ACCEPTED")
            .summary("s:x")
            .content("y")
            .build();

        ArchitecturalRecord rec2 = ArchitecturalRecord.builder()
            .id("ADR-001")
            .title("Title")
            .status("ACCEPTED")
            .summary("s")
            .content("x:y")
            .build();

        Object key1 = generator.generate(method, diff, List.of(rec1));
        Object key2 = generator.generate(method, diff, List.of(rec2));

        assertThat(key1).isNotEqualTo(key2);
    }
}
