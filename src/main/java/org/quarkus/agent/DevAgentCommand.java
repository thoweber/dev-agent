package org.quarkus.agent;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.quarkus.agent.cli.InitCommand;
import org.quarkus.agent.cli.PlanCommand;
import org.quarkus.agent.cli.FeatureCommand;
import org.quarkus.agent.cli.StatusCommand;
import org.quarkus.agent.cli.LogsCommand;
import org.quarkus.agent.cli.ResumeCommand;
import org.quarkus.agent.cli.AbortCommand;
import org.quarkus.agent.cli.MetricsCommand;

/**
 * Top-level CLI entry point for dev-agent.
 */
@TopCommand
@Command(
    name = "dev-agent",
    description = "Autonomous full-stack software engineering CLI and quality orchestrator",
    mixinStandardHelpOptions = true,
    version = "dev-agent 1.0.0-SNAPSHOT",
    subcommands = {
        InitCommand.class,
        PlanCommand.class,
        FeatureCommand.class,
        StatusCommand.class,
        LogsCommand.class,
        ResumeCommand.class,
        AbortCommand.class,
        MetricsCommand.class
    }
)
@SuppressWarnings("java:S106")
public class DevAgentCommand implements Runnable {

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target repository workspace root", defaultValue = ".")
    private String dir = ".";

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose debug telemetry")
    private boolean verbose;

    @Override
    public void run() {
        System.out.println("dev-agent CLI 1.0.0-SNAPSHOT. Run with --help for available commands.");
    }

    public boolean isVerbose() {
        return verbose;
    }

    public String getDir() {
        return dir;
    }
}
