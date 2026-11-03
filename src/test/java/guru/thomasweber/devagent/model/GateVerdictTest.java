package guru.thomasweber.devagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GateVerdictTest {

    @Test
    @DisplayName("Given GateVerdict builder - when instantiated - then properties match and toBuilder works")
    void testGateVerdictBuilder() {
        GateVerdict verdict = GateVerdict.builder()
            .approved(true)
            .summary("Architecture check passed")
            .violations(List.of())
            .recommendations(List.of("Follow ADR-001"))
            .build();

        assertThat(verdict.approved()).isTrue();
        assertThat(verdict.summary()).isEqualTo("Architecture check passed");
        assertThat(verdict.violations()).isEmpty();
        assertThat(verdict.recommendations()).containsExactly("Follow ADR-001");

        GateVerdict updated = verdict.toBuilder()
            .approved(false)
            .violations(List.of("Missing validation"))
            .build();

        assertThat(updated.approved()).isFalse();
        assertThat(updated.summary()).isEqualTo("Architecture check passed");
        assertThat(updated.violations()).containsExactly("Missing validation");
    }

    @Test
    @DisplayName("Given null violations or recommendations - when instantiated - then initializes to empty immutable lists")
    void givenNullLists_whenInstantiated_thenInitializesToEmptyLists() {
        GateVerdict verdict = new GateVerdict(true, "Check", null, null);
        assertThat(verdict.violations()).isEmpty();
        assertThat(verdict.recommendations()).isEmpty();
    }
}
