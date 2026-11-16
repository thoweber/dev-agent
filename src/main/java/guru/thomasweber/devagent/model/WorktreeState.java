package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ephemeral heartbeat state serialized atomically into {@code .git/dev-agent-worktrees/<task-id>/state.json}.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record WorktreeState(
    String taskId,
    String projectId,
    TaskStatus status,
    String phase,
    long tokens,
    BigDecimal cost,
    Long pid,
    String branch,
    String worktreePath,
    Instant startedAt,
    Instant updatedAt,
    String lastMessage
) {
    public WorktreeState {
        if (cost == null) {
            cost = BigDecimal.ZERO;
        }
    }
}
