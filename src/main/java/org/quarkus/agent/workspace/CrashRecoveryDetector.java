package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkus.agent.entity.TaskRunEntity;
import org.quarkus.agent.entity.TaskStatus;

import java.util.Optional;

/**
 * Detects orphaned or interrupted background tasks whose OS processes terminated unexpectedly.
 */
@ApplicationScoped
public class CrashRecoveryDetector {

    @Inject
    ProjectIdResolver projectResolver;

    public Optional<TaskRunEntity> findResumableTask() {
        return findResumableTask(projectResolver.resolveProjectId());
    }

    public Optional<TaskRunEntity> findResumableTask(String projectId) {
        TaskRunEntity latest = TaskRunEntity.find(
            "projectId = ?1 and status not in (?2, ?3, ?4) order by updatedAt desc",
            projectId, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED
        ).firstResult();

        if (latest == null) {
            return Optional.empty();
        }

        // If PID is registered and still active on the host OS, task is running in another process
        if (latest.pid != null && ProcessHandle.of(latest.pid).isPresent()) {
            return Optional.empty();
        }

        return Optional.of(latest);
    }
}
