package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.BranchName;
import guru.thomasweber.devagent.model.TaskId;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorkerProcess;
import guru.thomasweber.devagent.model.WorktreeState;
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

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

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
    private final ProjectIdResolver projectResolver;
    private final DevAgentConfigLoader configLoader;
    private final WorktreeTaskExecutor taskExecutor;

    @Inject
    public WorkerCommand(
        StateSocketService socketService,
        ProjectIdResolver projectResolver,
        DevAgentConfigLoader configLoader,
        WorktreeTaskExecutor taskExecutor
    ) {
        this.socketService = socketService;
        this.projectResolver = projectResolver;
        this.configLoader = configLoader;
        this.taskExecutor = taskExecutor;
    }

    public WorkerCommand() {
        this(
            new StateSocketService(),
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
        PrintWriter err = getErr();

        WorkspaceContextResolver resolver = getWorkspaceContextResolver();
        Path repoRoot = resolver.resolveRepoRoot();
        socketService.resolveStateFile(repoRoot, taskId);

        Path worktreeDir = resolver.resolveWorktreeDir(taskId);
        if (!Files.isDirectory(worktreeDir)) {
            err.println("[dev-agent] Worktree directory does not exist: " + worktreeDir);
            return 1;
        }
        Path executionRoot = worktreeDir;
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

        WorktreeState terminalState = taskExecutor.executeInWorktree(
            repoRoot,
            executionRoot,
            runningState,
            null,
            instruction,
            TargetDir.of(config.build().backend().targetDir()),
            config.isolation().autoPruneOnComplete(),
            err
        );

        if (terminalState.status() == TaskStatus.COMPLETED) {
            return 0;
        }
        return 1;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getInstruction() {
        return instruction;
    }
}
