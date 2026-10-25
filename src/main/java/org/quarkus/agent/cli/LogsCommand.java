package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Dependent
@Unremovable
@Command(
    name = "logs",
    description = "Streams execution logs and audit records for an active or completed task",
    mixinStandardHelpOptions = true
)
public class LogsCommand implements Runnable {

    @Parameters(index = "0", description = "The task ID to stream logs for", arity = "0..1")
    private String taskId;

    @Option(names = {"-f", "--follow"}, description = "Follow live log output")
    private boolean follow;

    @Override
    public void run() {
        if (taskId == null || taskId.isBlank()) {
            System.out.println("[dev-agent] Please specify a task ID to display logs.");
            return;
        }
        System.out.println("[dev-agent] Streaming logs for task: " + taskId);
    }
}
