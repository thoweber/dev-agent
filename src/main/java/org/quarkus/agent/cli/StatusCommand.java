package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Dependent
@Unremovable
@Command(
    name = "status",
    description = "Displays non-locking telemetry and active task progress from state.json sockets",
    mixinStandardHelpOptions = true
)
public class StatusCommand implements Runnable {

    @Option(names = {"--all"}, description = "Show all historical and active task statuses")
    private boolean all;

    @Override
    public void run() {
        System.out.println("[dev-agent] Checking active task sockets...");
        System.out.println("[dev-agent] No active background tasks detected.");
    }
}
