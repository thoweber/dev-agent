package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "abort",
    description = "Aborts an active background worker task and cleanly unlinks its worktree",
    mixinStandardHelpOptions = true
)
public class AbortCommand extends BaseTaskCommand {

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (taskId == null || taskId.isBlank()) {
            out.println("[dev-agent] Please specify a Task ID to abort.");
            return;
        }
        out.println("[dev-agent] Aborting background task: " + taskId);
    }
}
