package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.BranchName;
import guru.thomasweber.devagent.model.TaskId;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorkerProcess;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import guru.thomasweber.devagent.workspace.DesktopAlertService;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
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

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

import static java.util.Objects.nonNull;

/**
 * Hidden Picocli subcommand invoked by {@link guru.thomasweber.devagent.workspace.DetachedWorkerLauncher}
 * to execute a task inside its isolated Git worktree while emitting {@code state.json} heartbeats.
 */
@Dependent
@Unremovable
@Command(
    name = "worker",
    hidden = true,
    description = "Executes an isolated background worker lifecycle",
    mixinStandardHelpOptions = true
)
public class WorkerCommand extends BaseAgentCommand implements Callable<Integer> {

    @Option(names = {"--task-id"}, required = true, description = "Task identifier")
    private String taskId;

    @Option(names = {"--instruction"}, defaultValue = "", description = "Feature instruction")
    private String instruction = "";

    private final StateSocketService socketService;
    private final StateSyncService stateSyncService;
    private final BackendBuildService buildService;
    private final DesktopAlertService alertService;
    private final ProjectIdResolver projectResolver;
    private final TaskPersistenceService persistenceService;
    private final DevAgentConfigLoader configLoader;
    private final GitWorktreeService worktreeService;

    @Inject
    public WorkerCommand(
        StateSocketService socketService,
        StateSyncService stateSyncService,
        BackendBuildService buildService,
        DesktopAlertService alertService,
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService,
        DevAgentConfigLoader configLoader,
        GitWorktreeService worktreeService
    ) {
        this.socketService = socketService;
        this.stateSyncService = stateSyncService;
        this.buildService = buildService;
        this.alertService = alertService;
        this.projectResolver = projectResolver;
        this.persistenceService = persistenceService;
        this.configLoader = configLoader;
        this.worktreeService = worktreeService;
    }

    public WorkerCommand(
        StateSocketService socketService,
        StateSyncService stateSyncService,
        BackendBuildService buildService,
        DesktopAlertService alertService,
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService,
        DevAgentConfigLoader configLoader
    ) {
        this(socketService, stateSyncService, buildService, alertService, projectResolver, persistenceService, configLoader, new GitWorktreeService());
    }

    public WorkerCommand(
        StateSocketService socketService,
        StateSyncService stateSyncService,
        BackendBuildService buildService,
        DesktopAlertService alertService,
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService
    ) {
        this(socketService, stateSyncService, buildService, alertService, projectResolver, persistenceService, new DevAgentConfigLoader(), new GitWorktreeService());
    }

    public WorkerCommand() {
        this(
            new StateSocketService(),
            new StateSyncService(),
            new BackendBuildService(new WorkspaceContextResolver(), new SubprocessExecutor()),
            new DesktopAlertService(),
            new ProjectIdResolver(),
            new TaskPersistenceService(),
            new DevAgentConfigLoader(),
            new GitWorktreeService()
        );
    }

    @Override
    public void run() {
        call();
    }

    @Override
    public Integer call() {
        PrintWriter out = getOut();
        PrintWriter err = getErr();

        WorkspaceContextResolver resolver = getWorkspaceContextResolver();
        Path repoRoot = resolver.resolveRepoRoot();
        socketService.resolveStateFile(repoRoot, taskId);

        Path worktreeDir = resolver.resolveWorktreeDir(taskId);
        Path executionRoot = resolveExecutionRoot(repoRoot, worktreeDir);
        String projectId = projectResolver.resolveForPath(repoRoot).idString();
        WorkerProcess currentProcess = WorkerProcess.current();
        Instant now = Instant.now();
        DevAgentConfig config = configLoader.loadForWorkspace(repoRoot);

        Optional<WorktreeState> existingOpt = socketService.readState(repoRoot, taskId);
        WorktreeState baseState = existingOpt.orElseGet(() -> WorktreeState.builder()
            .taskId(taskId)
            .projectId(projectId)
            .status(TaskStatus.PLANNING)
            .phase(TaskStatus.PLANNING.name())
            .pid(currentProcess.pid())
            .pidStartedAt(currentProcess.startedAt())
            .branch(BranchName.forTask(TaskId.of(taskId)).value())
            .worktreePath(executionRoot.toString())
            .startedAt(now)
            .updatedAt(now)
            .lastMessage("Worker started")
            .build());

        WorktreeState runningState = baseState.toBuilder()
            .status(TaskStatus.PLANNING)
            .phase(TaskStatus.PLANNING.name())
            .pid(currentProcess.pid())
            .pidStartedAt(currentProcess.startedAt())
            .worktreePath(executionRoot.toString())
            .updatedAt(Instant.now())
            .lastMessage("Worker running in isolated worktree")
            .build();
        socketService.writeHeartbeat(repoRoot, runningState);
        out.println("[dev-agent] Worker started for task " + taskId + " (PID " + currentProcess.pid() + ")");

        WorktreeState terminalState;
        try {
            terminalState = WorktreeExecutionContext.runInWorktree(
                executionRoot,
                () -> executeVerificationPhase(repoRoot, executionRoot, runningState, config)
            );
        } catch (Exception e) {
            err.println("[dev-agent] Worker execution failed for task " + taskId + ": " + e.getMessage());
            terminalState = runningState.toBuilder()
                .status(TaskStatus.FAILED)
                .phase(TaskStatus.FAILED.name())
                .updatedAt(Instant.now())
                .lastMessage("Worker failed: " + e.getMessage())
                .build();
        }

        socketService.writeHeartbeat(repoRoot, terminalState);
        persistTerminalRun(terminalState);
        alertService.notifyTaskOutcome(repoRoot, terminalState);
        if (terminalState.status() == TaskStatus.COMPLETED
            && config.isolation().autoPruneOnComplete()
            && !repoRoot.equals(executionRoot)) {
            worktreeService.removeWorktree(repoRoot, taskId, false, terminalState.branch());
        }

        if (terminalState.status() == TaskStatus.COMPLETED) {
            return 0;
        }
        return 1;
    }

    private WorktreeState executeVerificationPhase(
        Path repoRoot,
        Path executionRoot,
        WorktreeState runningState,
        DevAgentConfig config
    ) {
        WorktreeState verifying = runningState.toBuilder()
            .status(TaskStatus.VERIFYING)
            .phase(TaskStatus.VERIFYING.name())
            .updatedAt(Instant.now())
            .lastMessage("Verifying workspace build inside worktree")
            .build();
        socketService.writeHeartbeat(repoRoot, verifying);

        if (BackendBuildService.hasBackendBuildMarker(executionRoot)) {
            String targetDir = config.build().backend().targetDir();
            SanitizedLog log = buildService.compile(executionRoot, null, targetDir);
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
            .lastMessage("Task completed in isolated worktree")
            .build();
    }

    private void persistTerminalRun(WorktreeState terminalState) {
        try {
            Optional<TaskRun> existingOpt = persistenceService.findByProjectAndTask(
                terminalState.projectId(),
                terminalState.taskId()
            );
            TaskRun run = existingOpt
                .map(existing -> {
                    String resolvedStory = existing.userStory();
                    if (nonNull(instruction) && !instruction.isBlank()) {
                        resolvedStory = instruction;
                    }
                    return existing.toBuilder()
                        .status(terminalState.status())
                        .currentPhase(terminalState.phase())
                        .branch(Objects.requireNonNullElse(terminalState.branch(), existing.branch()))
                        .pid(terminalState.pid())
                        .updatedAt(terminalState.updatedAt())
                        .userStory(resolvedStory)
                        .build();
                })
                .orElseGet(() -> TaskRun.builder()
                    .projectId(terminalState.projectId())
                    .taskId(terminalState.taskId())
                    .status(terminalState.status())
                    .currentPhase(terminalState.phase())
                    .branch(terminalState.branch())
                    .pid(terminalState.pid())
                    .createdAt(terminalState.startedAt())
                    .updatedAt(terminalState.updatedAt())
                    .userStory(instruction)
                    .build());
            TaskRun saved = persistenceService.saveTask(run);
            if (nonNull(saved)) {
                stateSyncService.projectToMarkdown(saved);
            }
        } catch (RuntimeException _) {
            // Non-blocking fallback when persistence runtime is unavailable
        }
    }

    private Path resolveExecutionRoot(Path repoRoot, Path worktreeDir) {
        if (Files.isDirectory(worktreeDir)) {
            return worktreeDir;
        }
        return repoRoot;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getInstruction() {
        return instruction;
    }
}
