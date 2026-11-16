package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.TaskId;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import guru.thomasweber.devagent.workspace.DesktopAlertService;
import guru.thomasweber.devagent.workspace.DetachedWorkerLauncher;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
import guru.thomasweber.devagent.workspace.LocalWorkerLimiter;
import guru.thomasweber.devagent.workspace.ProjectIdResolver;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.StateSyncService;
import guru.thomasweber.devagent.workspace.SubprocessExecutor;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorktreeExecutionContext;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Dependent
@Unremovable
@Command(
    name = "feature",
    description = "Executes autonomous full-stack TDD implementation inside an isolated worktree",
    mixinStandardHelpOptions = true
)
public class FeatureCommand extends BaseAgentCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The feature story or task instruction", arity = "0..1")
    private String instruction;

    @Option(names = {"--gui"}, description = "Launch local Vert.x web review server on localhost:9999")
    private boolean gui;

    @Option(names = {"--detach"}, description = "Run execution in a detached background worker process")
    private boolean detach;

    @Option(names = {"-y", "--yes"}, description = "Automatically accept generated plan and steps without interactive confirmation")
    private boolean yes;

    @Option(names = {"--offline"}, description = "Run strictly with offline local Ollama models")
    private boolean offline;

    private final GitWorktreeService worktreeService;
    private final StateSocketService socketService;
    private final LocalWorkerLimiter workerLimiter;
    private final DetachedWorkerLauncher workerLauncher;
    private final DesktopAlertService alertService;
    private final BackendBuildService buildService;
    private final ProjectIdResolver projectResolver;
    private final TaskPersistenceService persistenceService;
    private final StateSyncService stateSyncService;
    private final DevAgentConfigLoader configLoader;

    @Inject
    public FeatureCommand(
        GitWorktreeService worktreeService,
        StateSocketService socketService,
        LocalWorkerLimiter workerLimiter,
        DetachedWorkerLauncher workerLauncher,
        DesktopAlertService alertService,
        BackendBuildService buildService,
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService,
        StateSyncService stateSyncService,
        DevAgentConfigLoader configLoader
    ) {
        this.worktreeService = worktreeService;
        this.socketService = socketService;
        this.workerLimiter = workerLimiter;
        this.workerLauncher = workerLauncher;
        this.alertService = alertService;
        this.buildService = buildService;
        this.projectResolver = projectResolver;
        this.persistenceService = persistenceService;
        this.stateSyncService = stateSyncService;
        this.configLoader = configLoader;
    }

    public FeatureCommand() {
        this(
            new GitWorktreeService(),
            new StateSocketService(),
            new LocalWorkerLimiter(),
            new DetachedWorkerLauncher(),
            new DesktopAlertService(),
            new BackendBuildService(new WorkspaceContextResolver(), new SubprocessExecutor()),
            new ProjectIdResolver(),
            new TaskPersistenceService(),
            new StateSyncService(),
            new DevAgentConfigLoader()
        );
    }

    @Override
    public void run() {
        call();
    }

    @Override
    public Integer call() {
        PrintWriter out = getOut();
        if (isNull(instruction) || instruction.isBlank()) {
            out.println("[dev-agent] Please provide a feature specification to implement.");
            return 0;
        }
        out.println("[dev-agent] Initiating full-stack TDD execution: " + instruction);

        WorkspaceContextResolver resolver = getWorkspaceContextResolver();
        Path repoRoot = resolver.resolveRepoRoot();
        DevAgentConfig config = configLoader.loadForWorkspace(repoRoot);
        boolean isolateInWorktree = worktreeService.isGitRepository(repoRoot)
            && (config.isolation().useWorktrees() || detach);

        if (!isolateInWorktree) {
            out.println("[dev-agent] Non-Git or non-isolated workspace detected; skipping worktree allocation.");
            return 0;
        }

        TaskId generatedTaskId = TaskId.random();
        String taskId = generatedTaskId.value();
        String targetDir = config.build().backend().targetDir();
        WorktreeAllocation allocation = worktreeService.allocateWorktree(
            repoRoot,
            taskId,
            instruction,
            config.isolation(),
            targetDir
        );
        String projectId = projectResolver.resolveForPath(repoRoot).idString();
        Instant now = Instant.now();

        long currentPid = ProcessHandle.current().pid();
        WorktreeState initialState = WorktreeState.builder()
            .taskId(taskId)
            .projectId(projectId)
            .status(TaskStatus.PLANNING)
            .phase(TaskStatus.PLANNING.name())
            .pid(currentPid)
            .pidStartedAt(StateSocketService.resolvePidStartInstant(currentPid))
            .branch(allocation.branch())
            .worktreePath(allocation.worktreePath().toString())
            .startedAt(now)
            .updatedAt(now)
            .lastMessage("Worktree allocated on branch " + allocation.branch())
            .build();
        socketService.writeHeartbeat(repoRoot, initialState);

        LocalWorkerLimiter.PermitGate gate = workerLimiter.openGate(config.maxConcurrentWorkers());
        return gate.executeWithPermit(repoRoot, initialState, () -> {
            if (detach) {
                return launchDetached(out, repoRoot, allocation, initialState);
            }
            return executeForeground(out, repoRoot, allocation, initialState, targetDir, config.isolation().autoPruneOnComplete());
        });
    }

    private Integer launchDetached(
        PrintWriter out,
        Path repoRoot,
        WorktreeAllocation allocation,
        WorktreeState initialState
    ) {
        long childPid = workerLauncher.launchWorker(repoRoot, allocation.taskId(), instruction);
        WorktreeState detachedState = initialState.toBuilder()
            .pid(childPid)
            .pidStartedAt(StateSocketService.resolvePidStartInstant(childPid))
            .updatedAt(Instant.now())
            .lastMessage("Detached worker started (PID " + childPid + ")")
            .build();
        socketService.writeHeartbeat(repoRoot, detachedState);
        saveTaskRunSafely(detachedState, allocation);

        out.printf(
            "[dev-agent] Detached worker started: task=%s branch=%s worktree=%s PID %d%n",
            allocation.taskId(),
            allocation.branch(),
            allocation.worktreePath(),
            childPid
        );
        return 0;
    }

    private Integer executeForeground(
        PrintWriter out,
        Path repoRoot,
        WorktreeAllocation allocation,
        WorktreeState initialState,
        String targetDir,
        boolean autoPruneOnComplete
    ) {
        WorktreeState terminalState;
        try {
            terminalState = WorktreeExecutionContext.runInWorktree(allocation.worktreePath(), () -> {
                WorktreeState verifying = initialState.toBuilder()
                    .status(TaskStatus.VERIFYING)
                    .phase(TaskStatus.VERIFYING.name())
                    .updatedAt(Instant.now())
                    .lastMessage("Verifying feature in isolated worktree")
                    .build();
                socketService.writeHeartbeat(repoRoot, verifying);

                if (BackendBuildService.hasBackendBuildMarker(allocation.worktreePath())) {
                    SanitizedLog log = buildService.compile(allocation.worktreePath(), null, targetDir);
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
            });
        } catch (Exception e) {
            terminalState = initialState.toBuilder()
                .status(TaskStatus.FAILED)
                .phase(TaskStatus.FAILED.name())
                .updatedAt(Instant.now())
                .lastMessage("Foreground execution failed: " + e.getMessage())
                .build();
        }

        socketService.writeHeartbeat(repoRoot, terminalState);
        saveTaskRunSafely(terminalState, allocation);
        alertService.notifyTaskOutcome(repoRoot, terminalState);
        if (terminalState.status() == TaskStatus.COMPLETED && autoPruneOnComplete) {
            worktreeService.removeWorktree(repoRoot, allocation.taskId(), false, allocation.branch());
        }
        out.println("[dev-agent] Task " + allocation.taskId() + " finished with status: " + terminalState.status());
        if (terminalState.status() == TaskStatus.COMPLETED) {
            return 0;
        }
        return 1;
    }

    private void saveTaskRunSafely(WorktreeState state, WorktreeAllocation allocation) {
        try {
            TaskRun run = TaskRun.builder()
                .projectId(state.projectId())
                .taskId(state.taskId())
                .status(state.status())
                .currentPhase(state.phase())
                .branch(allocation.branch())
                .baseCommit(allocation.baseCommit())
                .checkpointStash(allocation.checkpointStash())
                .pid(state.pid())
                .createdAt(state.startedAt())
                .updatedAt(state.updatedAt())
                .userStory(instruction)
                .build();
            TaskRun saved = persistenceService.saveTask(run);
            if (nonNull(saved)) {
                stateSyncService.projectToMarkdown(saved);
            }
        } catch (RuntimeException _) {
            // Non-blocking fallback when persistence runtime is uninitialized
        }
    }

    public String getInstruction() {
        return instruction;
    }

    public boolean isGui() {
        return gui;
    }

    public boolean isDetach() {
        return detach;
    }

    public boolean isYes() {
        return yes;
    }

    public boolean isOffline() {
        return offline;
    }
}
