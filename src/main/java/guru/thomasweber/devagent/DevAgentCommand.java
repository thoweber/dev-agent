package guru.thomasweber.devagent;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import guru.thomasweber.devagent.cli.InitCommand;
import guru.thomasweber.devagent.cli.PlanCommand;
import guru.thomasweber.devagent.cli.FeatureCommand;
import guru.thomasweber.devagent.cli.StatusCommand;
import guru.thomasweber.devagent.cli.LogsCommand;
import guru.thomasweber.devagent.cli.ResumeCommand;
import guru.thomasweber.devagent.cli.AbortCommand;
import guru.thomasweber.devagent.cli.MetricsCommand;

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
    @SuppressWarnings("java:S106")
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
