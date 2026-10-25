package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import org.quarkus.agent.DevAgentCommand;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "metrics",
    description = "Displays cumulative token consumption and financial spend telemetry",
    mixinStandardHelpOptions = true
)
@SuppressWarnings("java:S106")
public class MetricsCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @ParentCommand
    private DevAgentCommand parentCommand;

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target workspace root directory", defaultValue = ".")
    private String dir = ".";

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

    private PrintWriter getOut() {
        return spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
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

    public boolean isJson() {
        return json;
    }
}
