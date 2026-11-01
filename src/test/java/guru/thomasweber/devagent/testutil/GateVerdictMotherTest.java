package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.model.GateVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GateVerdictMotherTest {

    @Test
    @DisplayName("Given GateVerdictMother - when creating approved verdict - then returns valid approved verdict")
    void givenMother_whenCreatingApproved_thenReturnsApproved() {
        GateVerdict approved = GateVerdictMother.approved();
        assertThat(approved.approved()).isTrue();
        assertThat(approved.violations()).isEmpty();

        GateVerdict approvedCustom = GateVerdictMother.approved("All checks passed");
        assertThat(approvedCustom.approved()).isTrue();
        assertThat(approvedCustom.summary()).isEqualTo("All checks passed");
    }

    @Test
    @DisplayName("Given GateVerdictMother - when creating rejected verdict - then returns valid rejected verdict")
    void givenMother_whenCreatingRejected_thenReturnsRejected() {
        GateVerdict rejected = GateVerdictMother.rejected(List.of("Violation 1", "Violation 2"));
        assertThat(rejected.approved()).isFalse();
        assertThat(rejected.violations()).containsExactly("Violation 1", "Violation 2");

        GateVerdict rejectedFull = GateVerdictMother.rejected(
            "Rejected due to invariants",
            List.of("V1"),
            List.of("R1")
        );
        assertThat(rejectedFull.approved()).isFalse();
        assertThat(rejectedFull.summary()).isEqualTo("Rejected due to invariants");
        assertThat(rejectedFull.violations()).containsExactly("V1");
        assertThat(rejectedFull.recommendations()).containsExactly("R1");
    }
}
