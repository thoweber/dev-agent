package org.quarkus.agent.cli;

import picocli.CommandLine.Parameters;

/**
 * Base command for task-targeted CLI actions.
 */
public abstract class BaseTaskCommand extends BaseAgentCommand {

    @Parameters(index = "0", description = "The task ID", arity = "0..1")
    protected String taskId;

    public String getTaskId() {
        return taskId;
    }
}
