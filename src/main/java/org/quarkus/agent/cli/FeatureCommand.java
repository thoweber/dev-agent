package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "feature",
    description = "Executes autonomous full-stack TDD implementation inside an isolated worktree",
    mixinStandardHelpOptions = true
)
public class FeatureCommand extends BaseAgentCommand {

    @Parameters(index = "0", description = "The feature story or task instruction", arity = "0..1")
    private String instruction;

    @Option(names = {"--gui"}, description = "Launch local Vert.x web review server on localhost:9999")
    private boolean gui;

    @Option(names = {"--detach"}, description = "Run execution in a detached background worker process")
    private boolean detach;

    @Option(names = {"-y", "--yes"}, description = "Automatically accept generated plan and steps without interactive confirmation")
    private boolean yes;

    @Option(names = {"--offline"}, description = "Run strictly with offline local Ollama models")
    private boolean offline;

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (instruction == null || instruction.isBlank()) {
            out.println("[dev-agent] Please provide a feature specification to implement.");
            return;
        }
        out.println("[dev-agent] Initiating full-stack TDD execution: " + instruction);
    }

    public String getInstruction() {
        return instruction;
    }

    public boolean isGui() {
        return gui;
    }

    public boolean isDetach() {
        return detach;
    }

    public boolean isYes() {
        return yes;
    }

    public boolean isOffline() {
        return offline;
    }
}
