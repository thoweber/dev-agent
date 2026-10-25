package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Dependent
@Unremovable
@Command(
    name = "init",
    description = "Inspects the workspace and initializes declarative .agents configuration",
    mixinStandardHelpOptions = true
)
public class InitCommand implements Runnable {

    @Option(names = {"--force"}, description = "Force overwrite existing configuration files")
    private boolean force;

    @Override
    public void run() {
        System.out.println("[dev-agent] Initializing workspace configuration in .agents/...");
    }
}
