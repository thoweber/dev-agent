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
            TaskRun task = candidates.getFirst();
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
                    out.println("[dev-agent] Resuming task: " + task.taskId());
                } else {
                    out.println("[dev-agent] Resumption aborted.");
                }
            } catch (IOException _) {
                out.println("[dev-agent] Resumption aborted.");
            }
            return 0;
        }

        out.println("[dev-agent] Found " + candidates.size() + " interrupted tasks:");
        for (int i = 0; i < candidates.size(); i++) {
            TaskRun t = candidates.get(i);
            out.println("  [" + (i + 1) + "] " + t.taskId() + " (Phase: " + t.currentPhase() + ", Updated: " + t.updatedAt() + ")");
        }

        if (!isInteractive()) {
            out.println("[dev-agent] Non-interactive environment detected. Please specify a task ID to resume.");
            return 0;
        }

        out.print("Select task to resume [1-" + candidates.size() + "] or 'q' to abort: ");
        out.flush();
        try {
            String line = getIn().readLine();
            if (line != null && !line.trim().equalsIgnoreCase("q")) {
                int idx = Integer.parseInt(line.trim()) - 1;
                if (idx >= 0 && idx < candidates.size()) {
                    out.println("[dev-agent] Resuming task: " + candidates.get(idx).taskId());
                } else {
                    out.println("[dev-agent] Invalid selection.");
                }
            } else {
                out.println("[dev-agent] Resumption aborted.");
            }
        } catch (Exception _) {
            out.println("[dev-agent] Resumption aborted.");
        }
        return 0;
    }

    private Integer handleExplicitResume(PrintWriter out, PrintWriter err, String targetTaskId) {
        ProjectId projectId = projectResolver.resolveProjectId();
        Optional<TaskRun> taskOpt = persistenceService.findByProjectAndTask(projectId, targetTaskId);

        if (taskOpt.isEmpty()) {
            err.println("[dev-agent] Task not found: " + targetTaskId);
            return 1;
        }

        TaskRun task = taskOpt.get();
        if (task.status() != null && task.status().isTerminal()) {
            err.println("[dev-agent] Cannot resume " + targetTaskId + ": Task has already completed with status " + task.status());
            return 1;
        }

        if (task.pid() != null && ProcessHandle.of(task.pid()).isPresent()) {
            err.println("[dev-agent] Cannot resume " + targetTaskId + ": Process (PID " + task.pid() + ") is still actively running.");
            return 1;
        }

        Path worktreeDir = workspaceResolver.resolveWorktreeDir(targetTaskId);
        if (!Files.isDirectory(worktreeDir)) {
            err.println("[dev-agent] Cannot resume " + targetTaskId + ": Worktree directory .git/dev-agent-worktrees/" + targetTaskId + " is missing.");
            return 1;
        }

        out.println("[dev-agent] Resuming task: " + targetTaskId);
        return 0;
    }
}
