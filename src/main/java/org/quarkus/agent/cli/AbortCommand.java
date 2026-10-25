package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Dependent
@Unremovable
@Command(
    name = "abort",
    description = "Aborts an active background worker task and cleanly unlinks its worktree",
    mixinStandardHelpOptions = true
)
public class AbortCommand implements Runnable {

    @Parameters(index = "0", description = "The Task ID to abort (e.g. TASK-101)", arity = "0..1")
    private String taskId;

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target workspace root directory", defaultValue = ".")
    private String dir = ".";

    @Override
    public void run() {
        if (taskId == null || taskId.isBlank()) {
            System.out.println("[dev-agent] Please specify a Task ID to abort.");
            return;
        }
        System.out.println("[dev-agent] Aborting background task: " + taskId);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getDir() {
        return dir;
    }
}
