package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Dependent
@Unremovable
@Command(
    name = "plan",
    description = "Analyzes requirements and generates a phased implementation DAG",
    mixinStandardHelpOptions = true
)
public class PlanCommand implements Runnable {

    @Parameters(index = "0", description = "The user story, requirement, or feature specification", arity = "0..1")
    private String story;

    @Override
    public void run() {
        if (story == null || story.isBlank()) {
            System.out.println("[dev-agent] Please provide a requirement or story description to plan.");
            return;
        }
        System.out.println("[dev-agent] Generating phased plan for: " + story);
    }
}
