package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AbortCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given abort command - when executed with valid taskId - then outputs aborting message and exit code 0")
    void testAbortWithTaskId() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("TASK-101");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Aborting background task: TASK-101");
        assertThat(command.getTaskId()).isEqualTo("TASK-101");
    }

    @Test
    @DisplayName("Given abort command - when executed without taskId - then outputs warning to specify taskId")
    void testAbortWithoutTaskId() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a Task ID to abort.");
    }

    @Test
    @DisplayName("Given abort command - when executed with blank taskId - then outputs warning to specify taskId")
    void testAbortWithBlankTaskId() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("   ");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a Task ID to abort.");
    }

    @Test
    @DisplayName("Given abort command - when executed with --help - then outputs abort subcommand usage and exit code 0")
    void testHelpOption() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: abort",
            "[<taskId>]",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given abort command - when executed with --dir - then resolves workspace directory")
    void testDirectoryOption() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "TASK-202");

        assertThat(exitCode).isZero();
        assertThat(command.getTaskId()).isEqualTo("TASK-202");
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }
}
