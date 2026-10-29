package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given metrics command - when executed with default options - then outputs cumulative tokens and spend summary")
    void testDefaultExecution() {
        MetricsCommand command = new MetricsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Telemetry metrics: 0 tokens consumed ($0.00 USD).");
        assertThat(command.isJson()).isFalse();
    }

    @Test
    @DisplayName("Given metrics command - when executed with --json - then outputs JSON formatted telemetry payload")
    void testJsonExecution() {
        MetricsCommand command = new MetricsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--json");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("{\"totalTokens\":0,\"totalCostUsd\":0.00,\"cachedDecisions\":0}");
        assertThat(command.isJson()).isTrue();
    }

    @Test
    @DisplayName("Given metrics command - when executed with --help - then outputs metrics subcommand usage and exit code 0")
    void testHelpOption() {
        MetricsCommand command = new MetricsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: metrics",
            "--json",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given metrics command - when executed with --dir - then resolves workspace directory")
    void testDirectoryOption() {
        MetricsCommand command = new MetricsCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--json");

        assertThat(exitCode).isZero();
        assertThat(command.isJson()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }
}
