package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.ProjectId;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
import guru.thomasweber.devagent.workspace.ProjectIdResolver;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.isNull;

@Dependent
@Unremovable
@Command(
    name = "abort",
    description = "Aborts an active background worker task and cleanly unlinks its worktree",
    mixinStandardHelpOptions = true
)
public class AbortCommand extends BaseTaskCommand implements Callable<Integer> {

    @Option(names = {"--delete-branch"}, description = "Delete the task feature branch after unlinking the worktree")
    private boolean deleteBranch;

    private final StateSocketService socketService;
    private final GitWorktreeService worktreeService;
    private final TaskPersistenceService persistenceService;
    private final ProjectIdResolver projectResolver;

    @Inject
    public AbortCommand(
        StateSocketService socketService,
        GitWorktreeService worktreeService,
        TaskPersistenceService persistenceService,
        ProjectIdResolver projectResolver
    ) {
        this.socketService = socketService;
        this.worktreeService = worktreeService;
        this.persistenceService = persistenceService;
        this.projectResolver = projectResolver;
    }

    public AbortCommand() {
        this(new StateSocketService(), new GitWorktreeService(), new TaskPersistenceService(), new ProjectIdResolver());
    }

    @Override
    public void run() {
        call();
    }

    @Override
    public Integer call() {
        PrintWriter out = getOut();
        if (isNull(taskId) || taskId.isBlank()) {
            out.println("[dev-agent] Please specify a Task ID to abort.");
            return 0;
        }

        String trimmedId = taskId.trim();
        WorkspaceContextResolver resolver = getWorkspaceContextResolver();
        Path repoRoot = resolver.resolveRepoRoot();
        socketService.resolveStateFile(repoRoot, trimmedId);

        out.println("[dev-agent] Aborting background task: " + trimmedId);

        Optional<WorktreeState> socketOpt = socketService.readState(repoRoot, trimmedId);
        ProjectId projectId = projectResolver.resolveForPath(repoRoot);
        Optional<TaskRun> persistedOpt = findPersistedSafely(projectId.idString(), trimmedId);

        Long pid = socketOpt.map(WorktreeState::pid)
            .orElseGet(() -> persistedOpt.map(TaskRun::pid).orElse(null));
        Instant expectedPidStart = socketOpt.map(WorktreeState::pidStartedAt).orElse(null);
        String branch = socketOpt.map(WorktreeState::branch)
            .orElseGet(() -> persistedOpt.map(TaskRun::branch).orElse(null));

        terminateProcess(pid, expectedPidStart);

        if (socketOpt.isPresent()) {
            WorktreeState aborted = socketOpt.get().toBuilder()
                .status(TaskStatus.ABORTED)
                .phase(TaskStatus.ABORTED.name())
                .updatedAt(Instant.now())
                .lastMessage("Task aborted by user")
                .build();
            socketService.writeHeartbeat(repoRoot, aborted);
        }

        persistedOpt.ifPresent(run -> saveAbortedTaskSafely(run));
        worktreeService.removeWorktree(repoRoot, trimmedId, deleteBranch, branch);

        return 0;
    }

    private void terminateProcess(Long pid, Instant expectedStart) {
        if (isNull(pid) || pid <= 0 || pid == ProcessHandle.current().pid()) {
            return;
        }
        ProcessHandle.of(pid)
            .filter(handle -> StateSocketService.matchesPidStart(handle, expectedStart))
            .ifPresent(handle -> {
                handle.destroy();
                try {
                    handle.onExit().get(100L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException _) {
                    // Process still alive after grace period; fall through to destroyForcibly
                }
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
    }

    private Optional<TaskRun> findPersistedSafely(String projectId, String targetTaskId) {
        try {
            return persistenceService.findByProjectAndTask(projectId, targetTaskId);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private void saveAbortedTaskSafely(TaskRun run) {
        try {
            persistenceService.saveTask(run.toBuilder()
                .status(TaskStatus.ABORTED)
                .currentPhase(TaskStatus.ABORTED.name())
                .updatedAt(Instant.now())
                .build());
        } catch (RuntimeException _) {
            // Persistence fallback when DB runtime is inactive
        }
    }

    public boolean isDeleteBranch() {
        return deleteBranch;
    }
}
