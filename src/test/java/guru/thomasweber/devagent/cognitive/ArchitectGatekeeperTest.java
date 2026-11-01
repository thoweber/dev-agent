package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.GateVerdict;
import guru.thomasweber.devagent.testutil.GateVerdictMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
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
}
