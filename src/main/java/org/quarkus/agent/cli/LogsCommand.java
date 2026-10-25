package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import org.quarkus.agent.DevAgentCommand;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "logs",
    description = "Streams execution logs and audit records for an active or completed task",
    mixinStandardHelpOptions = true
)
@SuppressWarnings("java:S106")
public class LogsCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @ParentCommand
    private DevAgentCommand parentCommand;

    @Parameters(index = "0", description = "The task ID to stream logs for", arity = "0..1")
    private String taskId;

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target workspace root directory", defaultValue = ".")
    private String dir = ".";

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

    private PrintWriter getOut() {
        return spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getDir() {
        if (dir != null && !dir.equals(".") && !dir.isBlank()) {
            return dir;
        }
        if (parentCommand != null && parentCommand.getDir() != null) {
            return parentCommand.getDir();
        }
        return dir != null ? dir : ".";
    }

    public boolean isFollow() {
        return follow;
    }
}
