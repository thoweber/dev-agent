package guru.thomasweber.devagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import guru.thomasweber.devagent.DevAgentCommand;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BaseAgentCommandTest {

    @TempDir
    Path tempDir;

    static class DummyCommand extends BaseAgentCommand {
        @Override
        public void run() {
            // no-op
        }
    }

    @Test
    @DisplayName("Given custom in/out - BaseAgentCommand interacts correctly")
    void testBaseAgentCommandStreams() {
        DummyCommand cmd = new DummyCommand();
        cmd.dir = tempDir.toString();

        assertThat(cmd.getDir()).isEqualTo(tempDir.toString());
        assertThat(cmd.getWorkspaceRoot()).isNotNull();

        // Default getIn and isInteractive
        assertThat(cmd.getIn()).isNotNull();
        assertThat(cmd.getOut()).isNotNull();
        assertThat(cmd.getErr()).isNotNull();

        // Custom reader
        BufferedReader reader = new BufferedReader(new StringReader("test-input\n"));
        cmd.setIn(reader);
        assertThat(cmd.isInteractive()).isTrue();
        assertThat(cmd.getIn()).isSameAs(reader);

        // Parent command and context resolver
        DevAgentCommand parent = new DevAgentCommand();
        cmd.parentCommand = parent;
        cmd.dir = null;
        assertThat(cmd.getWorkspaceRoot()).isNotNull();
        assertThat(cmd.getWorkspaceContextResolver()).isNotNull();

        // Execution through CommandLine sets spec
        CommandLine cl = new CommandLine(cmd);
        cl.execute();
        assertThat(cmd.getOut()).isNotNull();
        assertThat(cmd.getErr()).isNotNull();
    }
}
