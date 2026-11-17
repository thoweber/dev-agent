package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.TaskId;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorkerProcess;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.workspace.DetachedWorkerLauncher;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
import guru.thomasweber.devagent.workspace.LocalWorkerLimiter;
import guru.thomasweber.devagent.workspace.ProjectIdResolver;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.TargetDir;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorktreeTaskExecutor;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import static java.util.Objects.isNull;

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
    private final ProjectIdResolver projectResolver;
    private final DevAgentConfigLoader configLoader;
    private final WorktreeTaskExecutor taskExecutor;

    @Inject
    public FeatureCommand(
        GitWorktreeService worktreeService,
        StateSocketService socketService,
        LocalWorkerLimiter workerLimiter,
        DetachedWorkerLauncher workerLauncher,
        ProjectIdResolver projectResolver,
        DevAgentConfigLoader configLoader,
        WorktreeTaskExecutor taskExecutor
    ) {
        this.worktreeService = worktreeService;
        this.socketService = socketService;
        this.workerLimiter = workerLimiter;
        this.workerLauncher = workerLauncher;
        this.projectResolver = projectResolver;
        this.configLoader = configLoader;
        this.taskExecutor = taskExecutor;
    }

    public FeatureCommand() {
        this(
            new GitWorktreeService(),
            new StateSocketService(),
            new LocalWorkerLimiter(),
            new DetachedWorkerLauncher(),
            new ProjectIdResolver(),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor()
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

        TaskId generatedTaskId = generateTaskId();
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

        WorkerProcess currentProcess = WorkerProcess.current();
        WorktreeState initialState = WorktreeState.builder()
            .taskId(taskId)
            .projectId(projectId)
            .status(TaskStatus.PLANNING)
            .phase(TaskStatus.PLANNING.name())
            .pid(currentProcess.pid())
            .pidStartedAt(currentProcess.startedAt())
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
        taskExecutor.persistTaskRun(initialState, allocation, instruction);
        int verbosityCount = getVerbosityCount();
        long childPid = verbosityCount > 0
            ? workerLauncher.launchWorker(repoRoot, allocation.taskId(), instruction, verbosityCount)
            : workerLauncher.launchWorker(repoRoot, allocation.taskId(), instruction);
        Optional<WorktreeState> currentSocket = socketService.readState(repoRoot, allocation.taskId());
        boolean stillInitialPlanning = currentSocket
            .map(state -> state.status() == TaskStatus.PLANNING && Objects.equals(state.pid(), initialState.pid()))
            .orElse(true);
        if (stillInitialPlanning) {
            WorkerProcess childProcess = WorkerProcess.of(childPid);
            WorktreeState detachedState = initialState.toBuilder()
                .pid(childProcess.pid())
                .pidStartedAt(childProcess.startedAt())
                .updatedAt(Instant.now())
                .lastMessage("Detached worker started (PID " + childPid + ")")
                .build();
            socketService.writeHeartbeat(repoRoot, detachedState);
            taskExecutor.persistTaskRun(detachedState, allocation, instruction);
        }

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
        WorktreeState terminalState = taskExecutor.executeInWorktree(
            WorktreeTaskExecutor.ExecutionRequest.builder()
                .repoRoot(repoRoot)
                .executionRoot(allocation.worktreePath())
                .initialState(initialState)
                .allocation(allocation)
                .instruction(instruction)
                .targetDir(TargetDir.of(targetDir))
                .autoPruneOnComplete(autoPruneOnComplete)
                .build()
        );
        out.println("[dev-agent] Task " + allocation.taskId() + " finished with status: " + terminalState.status());
        if (terminalState.status() == TaskStatus.COMPLETED) {
            return 0;
        }
        return 1;
    }

    private static TaskId generateTaskId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return TaskId.of("TASK-" + suffix);
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
