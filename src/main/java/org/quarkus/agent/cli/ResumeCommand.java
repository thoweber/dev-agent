package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "resume",
    description = "Resumes an interrupted task from its last persisted checkpoint",
    mixinStandardHelpOptions = true
)
public class ResumeCommand extends BaseTaskCommand {

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (taskId == null || taskId.isBlank()) {
            out.println("[dev-agent] Please specify a task ID to resume.");
            return;
        }
        out.println("[dev-agent] Resuming task: " + taskId);
    }
}
