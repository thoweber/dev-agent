package org.quarkus.agent.testutil;

import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;

import java.time.Instant;

/**
 * Object Mother factory providing canonical, behavior-focused fixtures for TaskRun domain aggregates.
 */
public final class TaskRunMother {

    public static final String DEFAULT_PROJECT_ID = "test-project-crash";
    public static final long DEAD_PID = 999999999L;

    private TaskRunMother() {}

    /**
     * Pre-configured builder with standard defaults (timestamps, retry count = 0, default project).
     */
    public static TaskRun.TaskRunBuilder builder(String taskId) {
        return TaskRun.builder()
            .projectId(DEFAULT_PROJECT_ID)
            .taskId(taskId)
            .branch("feature/" + taskId)
            .retryCount(0)
            .createdAt(Instant.now())
            .updatedAt(Instant.now());
    }

    public static TaskRun crashed(String taskId, TaskStatus status) {
        return builder(taskId)
            .status(status)
            .currentPhase(status.name())
            .pid(DEAD_PID)
            .build();
    }

    public static TaskRun crashed(String projectId, String taskId, TaskStatus status) {
        return builder(taskId)
            .projectId(projectId)
            .status(status)
            .currentPhase(status.name())
            .pid(DEAD_PID)
            .build();
    }

    public static TaskRun active(String taskId, long pid) {
        return builder(taskId)
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(pid)
            .build();
    }

    public static TaskRun active(String projectId, String taskId, long pid) {
        return builder(taskId)
            .projectId(projectId)
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(pid)
            .build();
    }

    public static TaskRun active(String taskId) {
        return active(taskId, ProcessHandle.current().pid());
    }

    public static TaskRun planning(String taskId) {
        return builder(taskId)
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(DEAD_PID)
            .build();
    }

    public static TaskRun planning(String projectId, String taskId) {
        return builder(taskId)
            .projectId(projectId)
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(DEAD_PID)
            .build();
    }

    public static TaskRun completed(String taskId) {
        return builder(taskId)
            .status(TaskStatus.COMPLETED)
            .currentPhase("COMPLETED")
            .pid(DEAD_PID)
            .build();
    }

    public static TaskRun completed(String projectId, String taskId) {
        return builder(taskId)
            .projectId(projectId)
            .status(TaskStatus.COMPLETED)
            .currentPhase("COMPLETED")
            .pid(DEAD_PID)
            .build();
    }

    public static TaskRun aborted(String taskId) {
        return builder(taskId)
            .status(TaskStatus.ABORTED)
            .currentPhase("ABORTED")
            .pid(DEAD_PID)
            .build();
    }
}
