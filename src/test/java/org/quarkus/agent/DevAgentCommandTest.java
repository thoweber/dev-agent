package org.quarkus.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quarkus.agent.cli.AbortCommand;
import org.quarkus.agent.cli.FeatureCommand;
import org.quarkus.agent.cli.LogsCommand;
import org.quarkus.agent.cli.MetricsCommand;
import org.quarkus.agent.cli.PlanCommand;
import org.quarkus.agent.cli.ResumeCommand;
import org.quarkus.agent.cli.StatusCommand;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentCommandTest {

    @TempDir
    Path tempDir;

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
        assertThat(output).contains(
            "dev-agent",
            "init",
            "plan",
            "feature",
            "status",
            "logs",
            "resume",
            "abort",
            "metrics"
        );
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
    @DisplayName("Given root command - when executed directly - then prints summary and returns exit code 0")
    void testRootCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("-v", "--dir=" + tempDir.toString());

        assertThat(exitCode).isZero();
        assertThat(command.isVerbose()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given root command - when executed with unrecognized subcommand - then returns error exit code")
    void testInvalidSubcommandReturnsErrorCode() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter err = new StringWriter();
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("unrecognized-subcommand");

        assertThat(exitCode).isNotZero();
        assertThat(err.toString()).contains("Unmatched argument at index 0");
    }

    @Test
    @DisplayName("Given all subcommands - when executed under root with --dir - then inherit parent dir correctly")
    void testSubcommandsInheritParentDir() {
        DevAgentCommand root = new DevAgentCommand();
        CommandLine cmd = new CommandLine(root);

        cmd.execute("--dir=" + tempDir.toString(), "status");
        StatusCommand status = cmd.getSubcommands().get("status").getCommand();
        assertThat(status.getDir()).isEqualTo(tempDir.toString());

        cmd.execute("--dir=" + tempDir.toString(), "metrics");
        MetricsCommand metrics = cmd.getSubcommands().get("metrics").getCommand();
        assertThat(metrics.getDir()).isEqualTo(tempDir.toString());

        cmd.execute("--dir=" + tempDir.toString(), "abort", "TASK-1");
        AbortCommand abort = cmd.getSubcommands().get("abort").getCommand();
        assertThat(abort.getDir()).isEqualTo(tempDir.toString());

        cmd.execute("--dir=" + tempDir.toString(), "plan", "Story");
        PlanCommand plan = cmd.getSubcommands().get("plan").getCommand();
        assertThat(plan.getDir()).isEqualTo(tempDir.toString());

        cmd.execute("--dir=" + tempDir.toString(), "logs", "TASK-1");
        LogsCommand logs = cmd.getSubcommands().get("logs").getCommand();
        assertThat(logs.getDir()).isEqualTo(tempDir.toString());

        cmd.execute("--dir=" + tempDir.toString(), "resume", "TASK-1");
        ResumeCommand resume = cmd.getSubcommands().get("resume").getCommand();
        assertThat(resume.getDir()).isEqualTo(tempDir.toString());

        cmd.execute("--dir=" + tempDir.toString(), "feature", "Story");
        FeatureCommand feature = cmd.getSubcommands().get("feature").getCommand();
        assertThat(feature.getDir()).isEqualTo(tempDir.toString());
    }
}
