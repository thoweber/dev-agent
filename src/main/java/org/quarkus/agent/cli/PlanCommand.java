package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import org.quarkus.agent.DevAgentCommand;

import java.io.PrintWriter;

@Dependent
@Unremovable
@Command(
    name = "plan",
    description = "Analyzes requirements and generates a phased implementation DAG",
    mixinStandardHelpOptions = true
)
@SuppressWarnings("java:S106")
public class PlanCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @ParentCommand
    private DevAgentCommand parentCommand;

    @Parameters(index = "0", description = "The user story, requirement, or feature specification", arity = "0..1")
    private String story;

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target workspace root directory", defaultValue = ".")
    private String dir = ".";

    @Option(names = {"-y", "--yes"}, description = "Automatically accept generated plan without interactive confirmation")
    private boolean yes;

    @Option(names = {"--offline"}, description = "Run strictly with offline local Ollama models")
    private boolean offline;

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (story == null || story.isBlank()) {
            out.println("[dev-agent] Please provide a requirement or story description to plan.");
            return;
        }
        out.println("[dev-agent] Generating phased plan for: " + story);
    }

    private PrintWriter getOut() {
        return spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    }

    public String getStory() {
        return story;
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

    public boolean isYes() {
        return yes;
    }

    public boolean isOffline() {
        return offline;
    }
}
