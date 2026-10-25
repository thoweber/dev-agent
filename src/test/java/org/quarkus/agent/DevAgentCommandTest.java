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

        int exitCode = cmd.execute("-v", "--dir=" + tempDir.toString());

        assertThat(exitCode).isZero();
        assertThat(command.isVerbose()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given status subcommand - when executed without and with --json - then outputs expected text")
    void testStatusCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("status", "--all");
        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("No active background tasks detected.");

        StringWriter swJson = new StringWriter();
        cmd.setOut(new PrintWriter(swJson));
        int jsonExit = cmd.execute("status", "--json");
        assertThat(jsonExit).isZero();
        assertThat(swJson.toString()).contains("{\"activeTasks\":[],\"message\":\"No active background tasks detected.\"}");
    }

    @Test
    @DisplayName("Given status command directly - when inspected - getters return parsed values")
    void testStatusCommandDirect() {
        StatusCommand status = new StatusCommand();
        CommandLine cmd = new CommandLine(status);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exit = cmd.execute("--dir=" + tempDir.toString(), "--all", "--json");
        assertThat(exit).isZero();
        assertThat(status.isAll()).isTrue();
        assertThat(status.isJson()).isTrue();
        assertThat(status.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given metrics subcommand - when executed with and without --json - then outputs expected metrics")
    void testMetricsCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("metrics");
        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("0 tokens consumed");

        StringWriter swJson = new StringWriter();
        cmd.setOut(new PrintWriter(swJson));
        int jsonExit = cmd.execute("metrics", "--json");
        assertThat(jsonExit).isZero();
        assertThat(swJson.toString()).contains("{\"totalTokens\":0,\"totalCostUsd\":0.00,\"cachedDecisions\":0}");
    }

    @Test
    @DisplayName("Given metrics command directly - when inspected - getters return parsed values")
    void testMetricsCommandDirect() {
        MetricsCommand metrics = new MetricsCommand();
        CommandLine cmd = new CommandLine(metrics);
        int exit = cmd.execute("--dir=" + tempDir.toString(), "--json");

        assertThat(exit).isZero();
        assertThat(metrics.isJson()).isTrue();
        assertThat(metrics.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given abort subcommand - when executed with and without taskId - then behaves accordingly")
    void testAbortCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw1 = new StringWriter();
        cmd.setOut(new PrintWriter(sw1));

        int exitCode1 = cmd.execute("abort", "TASK-101");
        assertThat(exitCode1).isZero();
        assertThat(sw1.toString()).contains("Aborting background task: TASK-101");

        StringWriter sw2 = new StringWriter();
        cmd.setOut(new PrintWriter(sw2));
        int exitCode2 = cmd.execute("abort");
        assertThat(exitCode2).isZero();
        assertThat(sw2.toString()).contains("Please specify a Task ID to abort.");
    }

    @Test
    @DisplayName("Given abort command directly - when inspected - getters return parsed values")
    void testAbortCommandDirect() {
        AbortCommand abort = new AbortCommand();
        CommandLine cmd = new CommandLine(abort);
        int exit = cmd.execute("--dir=" + tempDir.toString(), "TASK-202");

        assertThat(exit).isZero();
        assertThat(abort.getTaskId()).isEqualTo("TASK-202");
        assertThat(abort.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given plan subcommand - when executed with and without story - then handles input")
    void testPlanCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw1 = new StringWriter();
        cmd.setOut(new PrintWriter(sw1));

        int exitCode1 = cmd.execute("plan", "Create order service", "--yes", "--offline");
        assertThat(exitCode1).isZero();
        assertThat(sw1.toString()).contains("Generating phased plan for: Create order service");

        StringWriter sw2 = new StringWriter();
        cmd.setOut(new PrintWriter(sw2));
        int exitCode2 = cmd.execute("plan");
        assertThat(exitCode2).isZero();
        assertThat(sw2.toString()).contains("Please provide a requirement or story description to plan.");
    }

    @Test
    @DisplayName("Given plan command directly - when inspected - getters return parsed values")
    void testPlanCommandDirect() {
        PlanCommand plan = new PlanCommand();
        CommandLine cmd = new CommandLine(plan);
        int exit = cmd.execute("--dir=" + tempDir.toString(), "-y", "--offline", "Draft architecture");

        assertThat(exit).isZero();
        assertThat(plan.getStory()).isEqualTo("Draft architecture");
        assertThat(plan.isYes()).isTrue();
        assertThat(plan.isOffline()).isTrue();
        assertThat(plan.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given logs subcommand - when executed with and without taskId - then behaves accordingly")
    void testLogsCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw1 = new StringWriter();
        cmd.setOut(new PrintWriter(sw1));

        int exitCode1 = cmd.execute("logs", "TASK-101", "--follow");
        assertThat(exitCode1).isZero();
        assertThat(sw1.toString()).contains("Streaming logs for task: TASK-101");

        StringWriter sw2 = new StringWriter();
        cmd.setOut(new PrintWriter(sw2));
        int exitCode2 = cmd.execute("logs");
        assertThat(exitCode2).isZero();
        assertThat(sw2.toString()).contains("Please specify a task ID to display logs.");
    }

    @Test
    @DisplayName("Given logs command directly - when inspected - getters return parsed values")
    void testLogsCommandDirect() {
        LogsCommand logs = new LogsCommand();
        CommandLine cmd = new CommandLine(logs);
        int exit = cmd.execute("--dir=" + tempDir.toString(), "-f", "TASK-303");

        assertThat(exit).isZero();
        assertThat(logs.getTaskId()).isEqualTo("TASK-303");
        assertThat(logs.isFollow()).isTrue();
        assertThat(logs.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given resume subcommand - when executed with and without taskId - then behaves accordingly")
    void testResumeCommandExecution() {
        DevAgentCommand command = new DevAgentCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw1 = new StringWriter();
        cmd.setOut(new PrintWriter(sw1));

        int exitCode1 = cmd.execute("resume", "TASK-101");
        assertThat(exitCode1).isZero();
        assertThat(sw1.toString()).contains("Resuming task: TASK-101");

        StringWriter sw2 = new StringWriter();
        cmd.setOut(new PrintWriter(sw2));
        int exitCode2 = cmd.execute("resume");
        assertThat(exitCode2).isZero();
        assertThat(sw2.toString()).contains("Please specify a task ID to resume.");
    }

    @Test
    @DisplayName("Given resume command directly - when inspected - getters return parsed values")
    void testResumeCommandDirect() {
        ResumeCommand resume = new ResumeCommand();
        CommandLine cmd = new CommandLine(resume);
        int exit = cmd.execute("--dir=" + tempDir.toString(), "TASK-404");

        assertThat(exit).isZero();
        assertThat(resume.getTaskId()).isEqualTo("TASK-404");
        assertThat(resume.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given feature command - when executed with parent command - then inherits workspace dir")
    void testFeatureCommandInheritance() {
        DevAgentCommand root = new DevAgentCommand();
        CommandLine cmd = new CommandLine(root);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "feature", "Build authentication");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("Initiating full-stack TDD execution: Build authentication");
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
