package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.model.GateVerdict;

import java.util.Collections;
import java.util.List;

/**
 * Canonical test fixtures for {@link GateVerdict}.
 */
public final class GateVerdictMother {

    private GateVerdictMother() {}

    public static GateVerdict approved() {
        return approved("All architectural invariants satisfied.");
    }

    public static GateVerdict approved(String summary) {
        return GateVerdict.builder()
            .approved(true)
            .summary(summary)
            .violations(Collections.emptyList())
            .recommendations(Collections.emptyList())
            .build();
    }

    public static GateVerdict rejected(List<String> violations) {
        return rejected("Architectural violations detected.", violations, Collections.emptyList());
    }

    public static GateVerdict rejected(String summary, List<String> violations, List<String> recommendations) {
        return GateVerdict.builder()
            .approved(false)
            .summary(summary)
            .violations(violations != null ? violations : Collections.emptyList())
            .recommendations(recommendations != null ? recommendations : Collections.emptyList())
            .build();
    }
}
