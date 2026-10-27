package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.workspace.CrashRecoveryDetector;
import org.quarkus.agent.workspace.ProjectIdResolver;
import org.quarkus.agent.workspace.WorkspaceContextResolver;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Dependent
@Unremovable
@Command(
    name = "resume",
    description = "Resumes an interrupted task from its last persisted checkpoint",
    mixinStandardHelpOptions = true
)
public class ResumeCommand extends BaseTaskCommand implements Callable<Integer> {

    private static final String RESUMING_TASK_PREFIX = "[dev-agent] Resuming task: ";
    private static final String RESUMPTION_ABORTED = "[dev-agent] Resumption aborted.";
    private static final String CANNOT_RESUME_PREFIX = "[dev-agent] Cannot resume ";

    private final CrashRecoveryDetector crashDetector;
    private final ProjectIdResolver projectResolver;
    private final TaskPersistenceService persistenceService;
    private final WorkspaceContextResolver workspaceResolver;

    @Inject
    public ResumeCommand(
        CrashRecoveryDetector crashDetector,
        ProjectIdResolver projectResolver,
        TaskPersistenceService persistenceService,
        WorkspaceContextResolver workspaceResolver
    ) {
        this.crashDetector = crashDetector;
        this.projectResolver = projectResolver;
        this.persistenceService = persistenceService;
        this.workspaceResolver = workspaceResolver;
    }

    public ResumeCommand() {
        this(new CrashRecoveryDetector(), new ProjectIdResolver(), new TaskPersistenceService(), new WorkspaceContextResolver());
    }

    @Override
    public void run() {
        call();
    }

    @Override
    public Integer call() {
        PrintWriter out = getOut();
        PrintWriter err = getErr();

        if (taskId == null || taskId.isBlank()) {
            return handleAutoResume(out);
        }
        return handleExplicitResume(out, err, taskId.trim());
    }

    private Integer handleAutoResume(PrintWriter out) {
        ProjectId projectId = projectResolver.resolveProjectId();
        List<TaskRun> candidates = crashDetector.findAllResumableTasks(projectId);

        if (candidates.isEmpty()) {
            out.println("[dev-agent] No interrupted or crashed tasks found for this project. Please specify a task ID to resume.");
            return 0;
        }

        if (candidates.size() == 1) {
            return promptSingleCandidate(out, candidates.getFirst());
        }

        return promptMultiCandidates(out, candidates);
    }

    private Integer promptSingleCandidate(PrintWriter out, TaskRun task) {
        String phase = task.currentPhase() != null ? task.currentPhase() : task.status().name();
        out.println("[dev-agent] Found interrupted task " + task.taskId() + " in phase [" + phase + "].");

        if (!isInteractive()) {
            out.println("[dev-agent] Non-interactive environment detected. Run 'dev-agent resume " + task.taskId() + "' to resume.");
            return 0;
        }

        out.print("Resume this task? [y/N]: ");
        out.flush();
        try {
            String line = getIn().readLine();
            if (line != null && line.trim().equalsIgnoreCase("y")) {
                out.println(RESUMING_TASK_PREFIX + task.taskId());
                return 0;
            }
        } catch (IOException _) {
            // Fall through to aborted
        }
        out.println(RESUMPTION_ABORTED);
        return 1;
    }

    private Integer promptMultiCandidates(PrintWriter out, List<TaskRun> candidates) {
        out.println("[dev-agent] Found " + candidates.size() + " interrupted tasks:");
        for (int i = 0; i < candidates.size(); i++) {
            TaskRun t = candidates.get(i);
            out.println("  [" + (i + 1) + "] " + t.taskId() + " (Phase: " + t.currentPhase() + ", Updated: " + t.updatedAt() + ")");
        }

        if (!isInteractive()) {
            out.println("[dev-agent] Non-interactive environment detected. Please specify a task ID to resume.");
            return 0;
        }

        return promptSelection(out, candidates);
    }

    private Integer promptSelection(PrintWriter out, List<TaskRun> candidates) {
        out.print("Select task to resume [1-" + candidates.size() + "] or 'q' to abort: ");
        out.flush();
        try {
            String line = getIn().readLine();
            if (line != null && !line.trim().equalsIgnoreCase("q")) {
                int idx = Integer.parseInt(line.trim()) - 1;
                if (idx >= 0 && idx < candidates.size()) {
                    out.println(RESUMING_TASK_PREFIX + candidates.get(idx).taskId());
                    return 0;
                }
                out.println("[dev-agent] Invalid selection.");
                return 1;
            }
        } catch (Exception _) {
            // Fall through to aborted
        }
        out.println(RESUMPTION_ABORTED);
        return 1;
    }

    private Integer handleExplicitResume(PrintWriter out, PrintWriter err, String targetTaskId) {
        ProjectId projectId = projectResolver.resolveProjectId();
        Optional<TaskRun> taskOpt = persistenceService.findByProjectAndTask(projectId, targetTaskId);

        if (taskOpt.isEmpty()) {
            err.println("[dev-agent] Task not found: " + targetTaskId);
            return 1;
        }

        String validationError = validateTaskEligibility(taskOpt.get(), targetTaskId);
        if (validationError != null) {
            err.println(CANNOT_RESUME_PREFIX + targetTaskId + ": " + validationError);
            return 1;
        }

        out.println(RESUMING_TASK_PREFIX + targetTaskId);
        return 0;
    }

    private String validateTaskEligibility(TaskRun task, String targetTaskId) {
        if (task.status() != null && task.status().isTerminal()) {
            return "Task has already completed with status " + task.status();
        }
        if (task.pid() != null && ProcessHandle.of(task.pid()).isPresent()) {
            return "Process (PID " + task.pid() + ") is still actively running.";
        }
        Path worktreeDir = workspaceResolver.resolveWorktreeDir(targetTaskId);
        return Files.isDirectory(worktreeDir)
            ? null
            : "Worktree directory .git/dev-agent-worktrees/" + targetTaskId + " is missing.";
    }
}
