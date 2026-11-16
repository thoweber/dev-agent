package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import guru.thomasweber.devagent.workspace.DesktopAlertService;
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
import java.util.Optional;
import java.util.concurrent.Callable;

import static java.util.Objects.isNull;

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

    @Inject
    public WorkerCommand(
        StateSocketService socketService,
        StateSyncService stateSyncService,
        BackendBuildService buildService,
        DesktopAlertService alertService,
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService,
        DevAgentConfigLoader configLoader
    ) {
        this.socketService = socketService;
        this.stateSyncService = stateSyncService;
        this.buildService = buildService;
        this.alertService = alertService;
        this.projectResolver = projectResolver;
        this.persistenceService = persistenceService;
        this.configLoader = configLoader;
    }

    public WorkerCommand(
        StateSocketService socketService,
        StateSyncService stateSyncService,
        BackendBuildService buildService,
        DesktopAlertService alertService,
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService
    ) {
        this(socketService, stateSyncService, buildService, alertService, projectResolver, persistenceService, new DevAgentConfigLoader());
    }

    public WorkerCommand() {
        this(
            new StateSocketService(),
            new StateSyncService(),
            new BackendBuildService(new WorkspaceContextResolver(), new SubprocessExecutor()),
            new DesktopAlertService(),
            new ProjectIdResolver(),
            new TaskPersistenceService(),
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
        PrintWriter err = getErr();

        WorkspaceContextResolver resolver = getWorkspaceContextResolver();
        Path repoRoot = resolver.resolveRepoRoot();
        socketService.resolveStateFile(repoRoot, taskId);

        Path worktreeDir = resolver.resolveWorktreeDir(taskId);
        Path executionRoot = Files.isDirectory(worktreeDir) ? worktreeDir : repoRoot;
        String projectId = projectResolver.resolveForPath(repoRoot).idString();
        long currentPid = ProcessHandle.current().pid();
        Instant now = Instant.now();

        Optional<WorktreeState> existingOpt = socketService.readState(repoRoot, taskId);
        WorktreeState baseState = existingOpt.orElseGet(() -> WorktreeState.builder()
            .taskId(taskId)
            .projectId(projectId)
            .status(TaskStatus.PLANNING)
            .phase(TaskStatus.PLANNING.name())
            .pid(currentPid)
            .branch("feature/" + taskId)
            .worktreePath(executionRoot.toString())
            .startedAt(now)
            .updatedAt(now)
            .lastMessage("Worker started")
            .build());

        WorktreeState runningState = baseState.toBuilder()
            .status(TaskStatus.PLANNING)
            .phase(TaskStatus.PLANNING.name())
            .pid(currentPid)
            .worktreePath(executionRoot.toString())
            .updatedAt(Instant.now())
            .lastMessage("Worker running in isolated worktree")
            .build();
        socketService.writeHeartbeat(repoRoot, runningState);
        out.println("[dev-agent] Worker started for task " + taskId + " (PID " + currentPid + ")");

        WorktreeState terminalState;
        try {
            terminalState = WorktreeExecutionContext.runInWorktree(
                executionRoot,
                () -> executeVerificationPhase(repoRoot, executionRoot, runningState)
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

        return terminalState.status() == TaskStatus.COMPLETED ? 0 : 1;
    }

    private WorktreeState executeVerificationPhase(Path repoRoot, Path executionRoot, WorktreeState runningState) {
        WorktreeState verifying = runningState.toBuilder()
            .status(TaskStatus.VERIFYING)
            .phase(TaskStatus.VERIFYING.name())
            .updatedAt(Instant.now())
            .lastMessage("Verifying workspace build inside worktree")
            .build();
        socketService.writeHeartbeat(repoRoot, verifying);

        if (hasBackendBuildMarker(executionRoot)) {
            String targetDir = resolveTargetDir(repoRoot);
            SanitizedLog log = buildService.compile(executionRoot, null, targetDir);
            if (!isNull(log) && (log.exitCode() != 0 || log.hasErrors())) {
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

    private boolean hasBackendBuildMarker(Path executionRoot) {
        return Files.isRegularFile(executionRoot.resolve("pom.xml"))
            || Files.isRegularFile(executionRoot.resolve("build.gradle"))
            || Files.isRegularFile(executionRoot.resolve("build.gradle.kts"));
    }

    private String resolveTargetDir(Path repoRoot) {
        Path configFile = repoRoot.resolve(".agents").resolve("dev-agent.yaml");
        if (!Files.isRegularFile(configFile)) {
            return BackendBuildService.DEFAULT_TARGET_DIR;
        }
        return configLoader.load(configFile).build().backend().targetDir();
    }

    private void persistTerminalRun(WorktreeState terminalState) {
        try {
            TaskRun run = TaskRun.builder()
                .projectId(terminalState.projectId())
                .taskId(terminalState.taskId())
                .status(terminalState.status())
                .currentPhase(terminalState.phase())
                .branch(terminalState.branch())
                .pid(terminalState.pid())
                .createdAt(terminalState.startedAt())
                .updatedAt(terminalState.updatedAt())
                .userStory(instruction)
                .build();
            TaskRun saved = persistenceService.saveTask(run);
            if (!isNull(saved)) {
                stateSyncService.projectToMarkdown(saved);
            }
        } catch (RuntimeException _) {
            // Non-blocking fallback when persistence runtime is unavailable
        }
    }

    public String getTaskId() {
        return taskId;
    }

    public String getInstruction() {
        return instruction;
    }
}
