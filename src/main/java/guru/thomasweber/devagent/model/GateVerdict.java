package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import java.util.List;

/**
 * Standard verdict emitted by the Architectural Invariant Gatekeeper.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record GateVerdict(
    boolean approved,
    String summary,
    List<String> violations,
    List<String> recommendations
) {
    public GateVerdict {
        violations = (violations != null) ? List.copyOf(violations) : List.of();
        recommendations = (recommendations != null) ? List.copyOf(recommendations) : List.of();
    }
}
