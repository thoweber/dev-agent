package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Dependent
@Unremovable
@Command(
    name = "resume",
    description = "Resumes an interrupted task from its last persisted checkpoint",
    mixinStandardHelpOptions = true
)
public class ResumeCommand implements Runnable {

    @Parameters(index = "0", description = "The task ID to resume", arity = "0..1")
    private String taskId;

    @Override
    public void run() {
        if (taskId == null || taskId.isBlank()) {
            System.out.println("[dev-agent] Please specify a task ID to resume.");
            return;
        }
        System.out.println("[dev-agent] Resuming task: " + taskId);
    }
}
