package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.util.List;
import java.util.Objects;

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
        violations = List.copyOf(Objects.requireNonNullElse(violations, List.of()));
        recommendations = List.copyOf(Objects.requireNonNullElse(recommendations, List.of()));
    }
}
