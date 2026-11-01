package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.GateVerdict;
import guru.thomasweber.devagent.testutil.GateVerdictMother;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class GatekeeperCacheIT {

    @Inject
    ArchitectGatekeeper architectGatekeeper;

    @InjectMock
    UniversalArchitectGatekeeperAiService mockAiService;

    @Test
    @DisplayName("Given repeated evaluation of identical diff and ADRs - when evaluated - then returns cached verdict in 0ms without invoking AiService")
    void givenIdenticalInputs_whenEvaluated_thenReturnsCachedVerdict() {
        GateVerdict approved = GateVerdictMother.approved("Cached evaluation OK");
        when(mockAiService.evaluate(anyString(), anyString())).thenReturn(approved);

        String diff = "--- a/Main.java\n+++ b/Main.java\n@@ -1 +1 @@\n+class Main {}";
        String adrs = "ADR-001: Quarkus Picocli Native CLI";

        // Initial invocation: cache miss -> invokes mockAiService
        GateVerdict verdict1 = architectGatekeeper.evaluate(diff, adrs);
        assertThat(verdict1).isEqualTo(approved);
        verify(mockAiService, times(1)).evaluate(diff, adrs);

        // Second invocation with identical inputs: cache hit -> returns cached verdict, does NOT invoke mockAiService
        GateVerdict verdict2 = architectGatekeeper.evaluate(diff, adrs);
        assertThat(verdict2).isEqualTo(approved);
        verify(mockAiService, times(1)).evaluate(diff, adrs);

        // Third invocation with different diff: cache miss -> invokes mockAiService again
        String differentDiff = "--- a/Other.java\n+++ b/Other.java";
        GateVerdict verdict3 = architectGatekeeper.evaluate(differentDiff, adrs);
        assertThat(verdict3).isEqualTo(approved);
        verify(mockAiService, times(2)).evaluate(anyString(), anyString());
    }
}
