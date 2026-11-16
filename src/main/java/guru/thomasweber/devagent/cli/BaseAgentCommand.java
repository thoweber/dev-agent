package guru.thomasweber.devagent.cli;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import guru.thomasweber.devagent.DevAgentCommand;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;

import static java.util.Objects.nonNull;

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

    @SuppressWarnings("java:S8450")
    protected BufferedReader getIn() {
        if (in != null) {
            return in;
        }
        return new BufferedReader(new InputStreamReader(System.in));
    }

    protected boolean isInteractive() {
        Console console = System.console();
        return in != null || (console != null && console.isTerminal());
    }

    @SuppressWarnings("java:S106")
    protected PrintWriter getOut() {
        if (nonNull(spec)) {
            return spec.commandLine().getOut();
        }
        return new PrintWriter(System.out, true);
    }

    @SuppressWarnings("java:S106")
    protected PrintWriter getErr() {
        if (nonNull(spec)) {
            return spec.commandLine().getErr();
        }
        return new PrintWriter(System.err, true);
    }

    public Path getWorkspaceRoot() {
        String parentDir = null;
        if (nonNull(parentCommand)) {
            parentDir = parentCommand.getDir();
        }
        return WorkspaceContextResolver.resolveEffectiveRoot(dir, parentDir);
    }

    public String getDir() {
        return getWorkspaceRoot().toString();
    }

    public WorkspaceContextResolver getWorkspaceContextResolver() {
        return new WorkspaceContextResolver(getWorkspaceRoot());
    }
}
