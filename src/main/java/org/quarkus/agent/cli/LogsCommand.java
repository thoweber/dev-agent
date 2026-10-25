package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "logs",
    description = "Streams execution logs and audit records for an active or completed task",
    mixinStandardHelpOptions = true
)
public class LogsCommand extends BaseTaskCommand {

    @Option(names = {"-f", "--follow"}, description = "Follow live log output")
    private boolean follow;

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (taskId == null || taskId.isBlank()) {
            out.println("[dev-agent] Please specify a task ID to display logs.");
            return;
        }
        out.println("[dev-agent] Streaming logs for task: " + taskId);
    }

    public boolean isFollow() {
        return follow;
    }
}
