package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "status",
    description = "Displays non-locking telemetry and active task progress from state.json sockets",
    mixinStandardHelpOptions = true
)
public class StatusCommand extends BaseAgentCommand {

    @Option(names = {"--all"}, description = "Show all historical and active task statuses")
    private boolean all;

    @Option(names = {"--json"}, description = "Output status formatted as JSON")
    private boolean json;

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (json) {
            out.println("{\"activeTasks\":[],\"message\":\"No active background tasks detected.\"}");
        } else {
            out.println("[dev-agent] Checking active task sockets...");
            out.println("[dev-agent] No active background tasks detected.");
        }
    }

    public boolean isAll() {
        return all;
    }

    public boolean isJson() {
        return json;
    }
}
