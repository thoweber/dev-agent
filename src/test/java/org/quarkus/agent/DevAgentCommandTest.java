package org.quarkus.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentCommandTest {

    @Test
    @DisplayName("Given root command - when invoked with --help - then prints usage and all subcommands")
    void testHelpOptionDisplaysSubcommands() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        String output = sw.toString();
        assertThat(output).contains("dev-agent");
        assertThat(output).contains("init");
        assertThat(output).contains("plan");
        assertThat(output).contains("feature");
        assertThat(output).contains("status");
        assertThat(output).contains("logs");
        assertThat(output).contains("resume");
    }

    @Test
    @DisplayName("Given root command - when invoked with --version - then prints correct version")
    void testVersionOption() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--version");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("1.0.0-SNAPSHOT");
    }

    @Test
    @DisplayName("Given status subcommand - when executed - then returns exit code 0")
    void testStatusCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("status");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("Given init subcommand - when executed - then returns exit code 0")
    void testInitCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("init");

        assertThat(exitCode).isZero();
    }
}
