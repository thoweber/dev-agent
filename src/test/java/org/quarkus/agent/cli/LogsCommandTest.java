package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogsCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given logs command - when executed with valid taskId - then outputs streaming logs message and exit code 0")
    void testLogsWithTaskId() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("TASK-101");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Streaming logs for task: TASK-101");
        assertThat(command.getTaskId()).isEqualTo("TASK-101");
        assertThat(command.isFollow()).isFalse();
    }

    @Test
    @DisplayName("Given logs command - when executed with --follow flag - then parses follow flag as true")
    void testLogsWithFollowFlag() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("-f", "TASK-101");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Streaming logs for task: TASK-101");
        assertThat(command.isFollow()).isTrue();
    }

    @Test
    @DisplayName("Given logs command - when executed without taskId - then outputs warning to specify taskId")
    void testLogsWithoutTaskId() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a task ID to display logs.");
    }

    @Test
    @DisplayName("Given logs command - when executed with blank taskId - then outputs warning to specify taskId")
    void testLogsWithBlankTaskId() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("   ");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a task ID to display logs.");
    }

    @Test
    @DisplayName("Given logs command - when executed with --help - then outputs logs subcommand usage and exit code 0")
    void testHelpOption() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: logs",
            "-f, --follow",
            "[<taskId>]",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given logs command - when executed with --dir - then resolves workspace directory")
    void testDirectoryOption() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "-f", "TASK-303");

        assertThat(exitCode).isZero();
        assertThat(command.getTaskId()).isEqualTo("TASK-303");
        assertThat(command.isFollow()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }
}
