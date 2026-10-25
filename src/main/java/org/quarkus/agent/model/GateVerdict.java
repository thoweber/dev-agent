package org.quarkus.agent.model;

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
) {}
