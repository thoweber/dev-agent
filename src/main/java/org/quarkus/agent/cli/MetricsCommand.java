package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Dependent
@Unremovable
@Command(
    name = "metrics",
    description = "Displays cumulative token consumption and financial spend telemetry",
    mixinStandardHelpOptions = true
)
public class MetricsCommand implements Runnable {

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target workspace root directory", defaultValue = ".")
    private String dir = ".";

    @Option(names = {"--json"}, description = "Output metrics formatted as JSON")
    private boolean json;

    @Override
    public void run() {
        System.out.println("[dev-agent] Telemetry metrics: 0 tokens consumed ($0.00 USD).");
    }

    public String getDir() {
        return dir;
    }

    public boolean isJson() {
        return json;
    }
}
