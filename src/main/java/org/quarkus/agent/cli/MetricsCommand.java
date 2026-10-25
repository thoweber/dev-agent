package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "metrics",
    description = "Displays cumulative token consumption and financial spend telemetry",
    mixinStandardHelpOptions = true
)
public class MetricsCommand extends BaseAgentCommand {

    @Option(names = {"--json"}, description = "Output metrics formatted as JSON")
    private boolean json;

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (json) {
            out.println("{\"totalTokens\":0,\"totalCostUsd\":0.00,\"cachedDecisions\":0}");
        } else {
            out.println("[dev-agent] Telemetry metrics: 0 tokens consumed ($0.00 USD).");
        }
    }

    public boolean isJson() {
        return json;
    }
}
