package org.quarkus.agent.cli;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import org.quarkus.agent.DevAgentCommand;
import org.quarkus.agent.workspace.WorkspaceContextResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Base command providing inherited options and terminal I/O helpers.
 */
public abstract class BaseAgentCommand implements Runnable {

    @Spec
    protected CommandSpec spec;

    @ParentCommand
    protected DevAgentCommand parentCommand;

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target workspace root directory", defaultValue = ".")
    protected String dir = ".";

    private BufferedReader in;

    public void setIn(BufferedReader in) {
        this.in = in;
    }

    protected BufferedReader getIn() {
        if (in != null) {
            return in;
        }
        return new BufferedReader(new InputStreamReader(System.in));
    }

    protected boolean isInteractive() {
        return in != null || System.console() != null;
    }

    @SuppressWarnings("java:S106")
    protected PrintWriter getOut() {
        return spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    }

    @SuppressWarnings("java:S106")
    protected PrintWriter getErr() {
        return spec != null ? spec.commandLine().getErr() : new PrintWriter(System.err, true);
    }

    public Path getWorkspaceRoot() {
        return WorkspaceContextResolver.resolveEffectiveRoot(dir, parentCommand != null ? parentCommand.getDir() : null);
    }

    public String getDir() {
        return getWorkspaceRoot().toString();
    }

    public WorkspaceContextResolver getWorkspaceContextResolver() {
        return new WorkspaceContextResolver(getWorkspaceRoot());
    }
}
