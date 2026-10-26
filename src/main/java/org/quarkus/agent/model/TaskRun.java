package org.quarkus.agent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

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
        if (steps == null) {
            steps = List.of();
        } else {
            steps = List.copyOf(steps);
        }
    }
}
