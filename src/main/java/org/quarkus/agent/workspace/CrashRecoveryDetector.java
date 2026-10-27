package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Detects orphaned or interrupted background tasks whose OS processes terminated unexpectedly.
 */
@ApplicationScoped
public class CrashRecoveryDetector {

    private final ProjectIdResolver projectResolver;
    private final TaskPersistenceService persistenceService;
    private final WorkspaceContextResolver workspaceResolver;

    @Inject
    public CrashRecoveryDetector(
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService,
        WorkspaceContextResolver workspaceResolver
    ) {
        this.projectResolver = projectResolver;
        this.persistenceService = persistenceService;
        this.workspaceResolver = workspaceResolver;
    }

    public CrashRecoveryDetector() {
        this(new ProjectIdResolver(), new TaskPersistenceService(), new WorkspaceContextResolver());
    }

    public Path resolveWorktreeDir(String taskId) {
        return workspaceResolver.resolveWorktreeDir(taskId);
    }

    public boolean isTaskResumable(TaskRun task) {
        if (task == null || task.status() == null || task.status().isTerminal()) {
            return false;
        }
        if (task.pid() != null && ProcessHandle.of(task.pid()).isPresent()) {
            return false;
        }
        Path worktreeDir = resolveWorktreeDir(task.taskId());
        return Files.isDirectory(worktreeDir);
    }

    public List<TaskRun> findAllResumableTasks() {
        return findAllResumableTasks(projectResolver.resolveProjectId());
    }

    public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
        return findAllResumableTasks(projectId.idString());
    }

    public List<TaskRun> findAllResumableTasks(String projectId) {
        List<TaskRun> candidates = persistenceService.findActiveCandidates(projectId);
        return candidates.stream().filter(this::isTaskResumable).toList();
    }

    public Optional<TaskRun> findResumableTaskById(ProjectId projectId, String taskId) {
        return findResumableTaskById(projectId.idString(), taskId);
    }

    public Optional<TaskRun> findResumableTaskById(String projectId, String taskId) {
        Optional<TaskRun> candidate = persistenceService.findByProjectAndTask(projectId, taskId);
        return candidate.filter(this::isTaskResumable);
    }

    public Optional<TaskRun> findResumableTask() {
        return findAllResumableTasks().stream().findFirst();
    }

    public Optional<TaskRun> findResumableTask(ProjectId projectId) {
        return findAllResumableTasks(projectId).stream().findFirst();
    }

    public Optional<TaskRun> findResumableTask(String projectId) {
        return findAllResumableTasks(projectId).stream().findFirst();
    }
}
