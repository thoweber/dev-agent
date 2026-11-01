package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.model.GateVerdict;
import guru.thomasweber.devagent.testutil.ArchitecturalRecordMother;
import guru.thomasweber.devagent.testutil.GateVerdictMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchitectGatekeeperTest {

    @Test
    @DisplayName("Given ArchitectGatekeeper - when evaluate called - then delegates to UniversalArchitectGatekeeperAiService")
    void givenGatekeeper_whenEvaluate_thenDelegatesToAiService() {
        UniversalArchitectGatekeeperAiService mockAiService = Mockito.mock(UniversalArchitectGatekeeperAiService.class);
        ArchitectGatekeeper gatekeeper = new ArchitectGatekeeper(mockAiService);

        GateVerdict expected = GateVerdictMother.approved("All rules pass");
        when(mockAiService.evaluate("diff-content", "adrs-content")).thenReturn(expected);

        GateVerdict actual = gatekeeper.evaluate("diff-content", "adrs-content");

        assertThat(actual).isEqualTo(expected);
        verify(mockAiService).evaluate("diff-content", "adrs-content");
    }

    @Test
    @DisplayName("Given ArchitectGatekeeper with List of records - when evaluate called - then formats prompt and delegates")
    void givenGatekeeperWithRecords_whenEvaluate_thenFormatsAndDelegates() {
        UniversalArchitectGatekeeperAiService mockAiService = Mockito.mock(UniversalArchitectGatekeeperAiService.class);
        ArchitectGatekeeper gatekeeper = new ArchitectGatekeeper(mockAiService);

        GateVerdict expected = GateVerdictMother.approved("All rules pass");
        when(mockAiService.evaluate(eq("diff-content"), any(String.class))).thenReturn(expected);

        ArchitecturalRecord rec1 = ArchitecturalRecordMother.adr001();
        ArchitecturalRecord rec2 = ArchitecturalRecordMother.adr003();
        ArchitecturalRecord rec3 = rec1.toBuilder().summary(null).content("   ").build();

        GateVerdict actual = gatekeeper.evaluate("diff-content", List.of(rec1, rec2, rec3));
        assertThat(actual).isEqualTo(expected);

        // Edge case: null or empty records
        gatekeeper.evaluate("diff-content", (List<ArchitecturalRecord>) null);
        gatekeeper.evaluate("diff-content", List.of());
    }
}
