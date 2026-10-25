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
    name = "plan",
    description = "Analyzes requirements and generates a phased implementation DAG",
    mixinStandardHelpOptions = true
)
public class PlanCommand extends BaseAgentCommand {

    @Parameters(index = "0", description = "The user story, requirement, or feature specification", arity = "0..1")
    private String story;

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

    public String getStory() {
        return story;
    }

    public boolean isYes() {
        return yes;
    }

    public boolean isOffline() {
        return offline;
    }
}
