package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Dependent
@Unremovable
@Command(
    name = "feature",
    description = "Executes autonomous full-stack TDD implementation inside an isolated worktree",
    mixinStandardHelpOptions = true
)
public class FeatureCommand implements Runnable {

    @Parameters(index = "0", description = "The feature story or task instruction", arity = "0..1")
    private String instruction;

    @Option(names = {"--gui"}, description = "Launch local Vert.x web review server on localhost:9999")
    private boolean gui;

    @Option(names = {"--offline"}, description = "Run strictly with offline local Ollama models")
    private boolean offline;

    @Override
    public void run() {
        if (instruction == null || instruction.isBlank()) {
            System.out.println("[dev-agent] Please provide a feature specification to implement.");
            return;
        }
        System.out.println("[dev-agent] Initiating full-stack TDD execution: " + instruction);
    }
}
