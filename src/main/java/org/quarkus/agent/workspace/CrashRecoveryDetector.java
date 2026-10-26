package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.TaskRun;

import java.util.List;
import java.util.Optional;

/**
 * Detects orphaned or interrupted background tasks whose OS processes terminated unexpectedly.
 */
@ApplicationScoped
public class CrashRecoveryDetector {

    private final ProjectIdResolver projectResolver;
    private final TaskPersistenceService persistenceService;

    @Inject
    public CrashRecoveryDetector(ProjectIdResolver projectResolver, TaskPersistenceService persistenceService) {
        this.projectResolver = projectResolver;
        this.persistenceService = persistenceService;
    }

    public CrashRecoveryDetector() {
        this(new ProjectIdResolver(), new TaskPersistenceService());
    }

    public Optional<TaskRun> findResumableTask() {
        return findResumableTask(projectResolver.resolveProjectId());
    }

    public Optional<TaskRun> findResumableTask(String projectId) {
        List<TaskRun> candidates = persistenceService.findActiveCandidates(projectId);

        for (TaskRun candidate : candidates) {
            // If candidate has no PID, or the registered PID is dead on the host OS, it is resumable
            if (candidate.pid() == null || ProcessHandle.of(candidate.pid()).isEmpty()) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }
}
