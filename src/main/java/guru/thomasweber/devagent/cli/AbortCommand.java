package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.ProjectId;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorkerProcess;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
import guru.thomasweber.devagent.workspace.ProjectIdResolver;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.StateSyncService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

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
    private final StateSyncService stateSyncService;

    @Inject
    public AbortCommand(
        StateSocketService socketService,
        GitWorktreeService worktreeService,
        TaskPersistenceService persistenceService,
        ProjectIdResolver projectResolver,
        StateSyncService stateSyncService
    ) {
        this.socketService = socketService;
        this.worktreeService = worktreeService;
        this.persistenceService = persistenceService;
        this.projectResolver = projectResolver;
        this.stateSyncService = stateSyncService;
    }

    public AbortCommand() {
        this(
            new StateSocketService(),
            new GitWorktreeService(),
            new TaskPersistenceService(),
            new ProjectIdResolver(),
            new StateSyncService()
        );
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

        WorkerProcess.from(pid, expectedPidStart)
            .ifPresent(process -> process.terminateGracefully(Duration.ofMillis(100)));

        if (socketOpt.isPresent()) {
            WorktreeState aborted = socketOpt.get().toBuilder()
                .status(TaskStatus.ABORTED)
                .phase(TaskStatus.ABORTED.name())
                .updatedAt(Instant.now())
                .lastMessage("Task aborted by user")
                .build();
            socketService.writeHeartbeat(repoRoot, aborted);
        }

        TaskRun abortedRun = buildAbortedRun(projectId, trimmedId, persistedOpt, socketOpt, pid, branch);
        persistedOpt.ifPresent(_ -> saveAbortedTaskSafely(abortedRun));
        if (nonNull(abortedRun)) {
            syncAbortJournalSafely(repoRoot, abortedRun);
        }
        worktreeService.removeWorktree(repoRoot, trimmedId, deleteBranch, branch);

        return 0;
    }

    private TaskRun buildAbortedRun(
        ProjectId projectId,
        String trimmedId,
        Optional<TaskRun> persistedOpt,
        Optional<WorktreeState> socketOpt,
        Long pid,
        String branch
    ) {
        Instant now = Instant.now();
        if (persistedOpt.isPresent()) {
            return persistedOpt.get().toBuilder()
                .status(TaskStatus.ABORTED)
                .currentPhase(TaskStatus.ABORTED.name())
                .updatedAt(now)
                .build();
        }
        if (socketOpt.isPresent()) {
            return TaskRun.builder()
                .projectId(projectId.idString())
                .taskId(trimmedId)
                .status(TaskStatus.ABORTED)
                .currentPhase(TaskStatus.ABORTED.name())
                .branch(branch)
                .pid(pid)
                .createdAt(socketOpt.get().startedAt())
                .updatedAt(now)
                .build();
        }
        return null;
    }

    private Optional<TaskRun> findPersistedSafely(String projectId, String targetTaskId) {
        try {
            return persistenceService.findByProjectAndTask(projectId, targetTaskId);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private void saveAbortedTaskSafely(TaskRun abortedRun) {
        try {
            persistenceService.saveTask(abortedRun);
        } catch (RuntimeException _) {
            // Persistence fallback when DB runtime is inactive
        }
    }

    private void syncAbortJournalSafely(Path repoRoot, TaskRun abortedRun) {
        try {
            stateSyncService.projectToMarkdown(repoRoot, abortedRun);
        } catch (RuntimeException _) {
            // Best-effort Markdown journal projection on abort
        }
    }

    public boolean isDeleteBranch() {
        return deleteBranch;
    }
}
