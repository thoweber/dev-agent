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

    private final ProjectIdResolver projectResolver;

    @Inject
    public CrashRecoveryDetector(ProjectIdResolver projectResolver) {
        this.projectResolver = projectResolver;
    }

    public CrashRecoveryDetector() {
        this(new ProjectIdResolver());
    }

    public Optional<TaskRunEntity> findResumableTask() {
        return findResumableTask(projectResolver.resolveProjectId());
    }

    public Optional<TaskRunEntity> findResumableTask(String projectId) {
        java.util.List<TaskRunEntity> candidates = TaskRunEntity.list(
            "projectId = ?1 and status not in (?2, ?3, ?4) order by updatedAt desc",
            projectId, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED
        );

        for (TaskRunEntity candidate : candidates) {
            // If candidate has no PID, or the registered PID is dead on the host OS, it is resumable
            if (candidate.pid == null || ProcessHandle.of(candidate.pid).isEmpty()) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }
}
