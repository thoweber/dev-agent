package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * JSON payload emitted by {@code dev-agent status --json} without opening JDBC database connections.
 */
@RegisterForReflection
public record StatusPayload(
    List<WorktreeState> activeTasks,
    String message
) {
    public StatusPayload {
        if (activeTasks == null) {
            activeTasks = List.of();
        } else {
            activeTasks = List.copyOf(activeTasks);
        }
    }
}
