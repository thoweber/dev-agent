package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StatusCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given status command - when executed with default options - then outputs non-locking socket checking message and exit code 0")
    void testDefaultExecution() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "[dev-agent] Checking active task sockets...",
            "[dev-agent] No active background tasks detected."
        );
        assertThat(command.isJson()).isFalse();
        assertThat(command.isAll()).isFalse();
    }

    @Test
    @DisplayName("Given status command - when executed with --json - then outputs JSON formatted payload with empty active tasks")
    void testJsonExecution() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--json");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("{\"activeTasks\":[],\"message\":\"No active background tasks detected.\"}");
        assertThat(command.isJson()).isTrue();
    }

    @Test
    @DisplayName("Given status command - when executed with --all - then flags isAll as true")
    void testAllFlagParsing() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--all");

        assertThat(exitCode).isZero();
        assertThat(command.isAll()).isTrue();
        assertThat(sw.toString()).contains("[dev-agent] No active background tasks detected.");
    }

    @Test
    @DisplayName("Given status command - when executed with --help - then outputs status subcommand usage and exit code 0")
    void testHelpOption() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: status",
            "--all",
            "--json",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given status command - when executed with --dir - then resolves workspace root directory")
    void testDirectoryOption() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--all", "--json");

        assertThat(exitCode).isZero();
        assertThat(command.isAll()).isTrue();
        assertThat(command.isJson()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }
}
