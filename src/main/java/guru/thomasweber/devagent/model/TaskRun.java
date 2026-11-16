package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Domain representation of an engineering task execution run.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record TaskRun(
    Long id,
    String projectId,
    String taskId,
    TaskStatus status,
    String currentPhase,
    String branch,
    String baseCommit,
    String checkpointStash,
    Long pid,
    int retryCount,
    Instant createdAt,
    Instant updatedAt,
    String userStory,
    String approvedPlanJson,
    List<StepAudit> steps
) {
    public TaskRun {
        steps = List.copyOf(Objects.requireNonNullElse(steps, List.of()));
    }
}
