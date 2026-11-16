package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Coordinates isolated worktree verification, terminal state heartbeat emission, merged H2
 * persistence with Markdown projection, desktop completion notifications, and worktree pruning.
 */
@ApplicationScoped
public class WorktreeTaskExecutor {

    private final StateSocketService socketService;
    private final StateSyncService stateSyncService;
    private final BackendBuildService buildService;
    private final DesktopAlertService alertService;
    private final TaskPersistenceService persistenceService;
    private final GitWorktreeService worktreeService;
    private final SecretRedactionFilter secretFilter = new SecretRedactionFilter();

    @Inject
    public WorktreeTaskExecutor(
        StateSocketService socketService,
        StateSyncService stateSyncService,
        BackendBuildService buildService,
        DesktopAlertService alertService,
        TaskPersistenceService persistenceService,
        GitWorktreeService worktreeService
    ) {
        this.socketService = socketService;
        this.stateSyncService = stateSyncService;
        this.buildService = buildService;
        this.alertService = alertService;
        this.persistenceService = persistenceService;
        this.worktreeService = worktreeService;
    }

    public WorktreeTaskExecutor() {
        this(
            new StateSocketService(),
            new StateSyncService(),
            new BackendBuildService(new WorkspaceContextResolver(), new SubprocessExecutor()),
            new DesktopAlertService(),
            new TaskPersistenceService(),
            new GitWorktreeService()
        );
    }

    public WorktreeState executeInWorktree(
        Path repoRoot,
        Path executionRoot,
        WorktreeState initialState,
        WorktreeAllocation allocation,
        String instruction,
        TargetDir targetDir,
        boolean autoPruneOnComplete,
        PrintWriter err
    ) {
        TargetDir effectiveTarget = Objects.requireNonNullElse(targetDir, TargetDir.DEFAULT);
        WorktreeState terminalState;
        try {
            terminalState = WorktreeExecutionContext.runInWorktree(
                executionRoot,
                () -> verifyWorkspaceBuild(repoRoot, executionRoot, initialState, effectiveTarget)
            );
        } catch (Exception e) {
            String redactedError = secretFilter.sanitize(Objects.toString(e.getMessage(), e.getClass().getSimpleName())).value();
            if (nonNull(err)) {
                err.println("[dev-agent] Worker execution failed for task " + initialState.taskId() + ": " + redactedError);
            }
            terminalState = initialState.toBuilder()
                .status(TaskStatus.FAILED)
                .phase(TaskStatus.FAILED.name())
                .updatedAt(Instant.now())
                .lastMessage("Execution failed: " + redactedError)
                .build();
        }

        socketService.writeHeartbeat(repoRoot, terminalState);
        persistTaskRun(terminalState, allocation, instruction);
        alertService.notifyTaskOutcome(repoRoot, terminalState);

        if (terminalState.status() == TaskStatus.COMPLETED
            && autoPruneOnComplete
            && !repoRoot.equals(executionRoot)) {
            worktreeService.removeWorktree(repoRoot, terminalState.taskId(), false, terminalState.branch());
        }
        return terminalState;
    }

    public void persistTaskRun(WorktreeState state, WorktreeAllocation allocation, String instruction) {
        try {
            Optional<TaskRun> existingOpt = persistenceService.findByProjectAndTask(state.projectId(), state.taskId());
            TaskRun run = existingOpt
                .map(existing -> mergeExistingRun(existing, state, allocation, instruction))
                .orElseGet(() -> createNewRun(state, allocation, instruction));

            TaskRun saved = persistenceService.saveTask(run);
            if (nonNull(saved)) {
                stateSyncService.projectToMarkdown(saved);
            }
        } catch (RuntimeException _) {
            // Non-blocking fallback when persistence runtime is unavailable
        }
    }

    private WorktreeState verifyWorkspaceBuild(
        Path repoRoot,
        Path executionRoot,
        WorktreeState runningState,
        TargetDir targetDir
    ) {
        WorktreeState verifying = runningState.toBuilder()
            .status(TaskStatus.VERIFYING)
            .phase(TaskStatus.VERIFYING.name())
            .updatedAt(Instant.now())
            .lastMessage("Verifying workspace build inside worktree")
            .build();
        socketService.writeHeartbeat(repoRoot, verifying);

        if (buildService.hasApplicableBuildSystem(executionRoot)) {
            SanitizedLog log = buildService.compile(executionRoot, null, targetDir.value());
            if (nonNull(log) && (log.exitCode() != 0 || log.hasErrors())) {
                return verifying.toBuilder()
                    .status(TaskStatus.FAILED)
                    .phase(TaskStatus.FAILED.name())
                    .updatedAt(Instant.now())
                    .lastMessage("Build verification failed with exit code " + log.exitCode())
                    .build();
            }
        }

        return verifying.toBuilder()
            .status(TaskStatus.COMPLETED)
            .phase(TaskStatus.COMPLETED.name())
            .updatedAt(Instant.now())
            .lastMessage("Completed in isolated worktree")
            .build();
    }

    private TaskRun mergeExistingRun(
        TaskRun existing,
        WorktreeState state,
        WorktreeAllocation allocation,
        String instruction
    ) {
        String resolvedStory = existing.userStory();
        if (nonNull(instruction) && !instruction.isBlank()) {
            resolvedStory = instruction;
        }
        String baseCommit = existing.baseCommit();
        if (isNull(baseCommit) && nonNull(allocation)) {
            baseCommit = allocation.baseCommit();
        }
        String checkpointStash = existing.checkpointStash();
        if (isNull(checkpointStash) && nonNull(allocation)) {
            checkpointStash = allocation.checkpointStash();
        }
        return existing.toBuilder()
            .status(state.status())
            .currentPhase(state.phase())
            .branch(Objects.requireNonNullElse(state.branch(), existing.branch()))
            .baseCommit(baseCommit)
            .checkpointStash(checkpointStash)
            .pid(state.pid())
            .updatedAt(state.updatedAt())
            .userStory(resolvedStory)
            .build();
    }

    private TaskRun createNewRun(WorktreeState state, WorktreeAllocation allocation, String instruction) {
        String branch = state.branch();
        String baseCommit = null;
        String checkpointStash = null;
        if (nonNull(allocation)) {
            branch = Objects.requireNonNullElse(branch, allocation.branch());
            baseCommit = allocation.baseCommit();
            checkpointStash = allocation.checkpointStash();
        }
        return TaskRun.builder()
            .projectId(state.projectId())
            .taskId(state.taskId())
            .status(state.status())
            .currentPhase(state.phase())
            .branch(branch)
            .baseCommit(baseCommit)
            .checkpointStash(checkpointStash)
            .pid(state.pid())
            .createdAt(state.startedAt())
            .updatedAt(state.updatedAt())
            .userStory(instruction)
            .build();
    }
}
