package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Objects;

/**
 * JSON payload emitted by {@code dev-agent status --json} without opening JDBC database connections.
 */
@RegisterForReflection
public record StatusPayload(
    List<WorktreeState> activeTasks,
    String message
) {
    public StatusPayload {
        activeTasks = List.copyOf(Objects.requireNonNullElse(activeTasks, List.of()));
    }
}
