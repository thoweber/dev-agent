package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Object Mother factory providing canonical fixtures for {@link WorktreeState} domain records.
 */
public final class WorktreeStateMother {

    public static final String DEFAULT_PROJECT_ID = "test-project-socket";
    public static final long DEAD_PID = 999999999L;

    private WorktreeStateMother() {}

    public static WorktreeState.WorktreeStateBuilder builder(String taskId) {
        Instant now = Instant.now();
        return WorktreeState.builder()
            .taskId(taskId)
            .projectId(DEFAULT_PROJECT_ID)
            .status(TaskStatus.PLANNING)
            .phase("PLANNING")
            .tokens(0L)
            .cost(BigDecimal.ZERO)
            .branch("feature/" + taskId)
            .worktreePath(".git/dev-agent-worktrees/" + taskId)
            .startedAt(now)
            .updatedAt(now)
            .lastMessage("Initialized");
    }

    public static WorktreeState active(String taskId, long pid) {
        return builder(taskId)
            .status(TaskStatus.VERIFYING)
            .phase("VERIFYING")
            .pid(pid)
            .tokens(1200L)
            .cost(new BigDecimal("0.05"))
            .lastMessage("Executing Maven verification")
            .build();
    }

    public static WorktreeState queued(String taskId) {
        return builder(taskId)
            .status(TaskStatus.QUEUED)
            .phase("QUEUED")
            .pid(null)
            .lastMessage("Waiting for available worker permit")
            .build();
    }

    public static WorktreeState crashed(String taskId) {
        return builder(taskId)
            .status(TaskStatus.GREEN_PATCH)
            .phase("GREEN_PATCH")
            .pid(DEAD_PID)
            .lastMessage("Worker terminated unexpectedly")
            .build();
    }

    public static WorktreeState completed(String taskId) {
        return builder(taskId)
            .status(TaskStatus.COMPLETED)
            .phase("COMPLETED")
            .pid(DEAD_PID)
            .tokens(2500L)
            .cost(new BigDecimal("0.12"))
            .lastMessage("Task completed successfully")
            .build();
    }
}
