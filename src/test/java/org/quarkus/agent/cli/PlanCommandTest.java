package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given plan command - when executed with valid story description - then outputs generating phased plan message")
    void testPlanWithStory() {
        PlanCommand command = new PlanCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("Create order service");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Generating phased plan for: Create order service");
        assertThat(command.getStory()).isEqualTo("Create order service");
        assertThat(command.isYes()).isFalse();
        assertThat(command.isOffline()).isFalse();
    }

    @Test
    @DisplayName("Given plan command - when executed with --yes and --offline flags - then parses flags as true")
    void testPlanWithFlags() {
        PlanCommand command = new PlanCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("-y", "--offline", "Draft architecture");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Generating phased plan for: Draft architecture");
        assertThat(command.getStory()).isEqualTo("Draft architecture");
        assertThat(command.isYes()).isTrue();
        assertThat(command.isOffline()).isTrue();
    }

    @Test
    @DisplayName("Given plan command - when executed without story - then outputs warning to specify requirement or story")
    void testPlanWithoutStory() {
        PlanCommand command = new PlanCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please provide a requirement or story description to plan.");
    }

    @Test
    @DisplayName("Given plan command - when executed with blank story - then outputs warning to specify requirement or story")
    void testPlanWithBlankStory() {
        PlanCommand command = new PlanCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("   ");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please provide a requirement or story description to plan.");
    }

    @Test
    @DisplayName("Given plan command - when executed with --help - then outputs plan subcommand usage and exit code 0")
    void testHelpOption() {
        PlanCommand command = new PlanCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: plan",
            "[<story>]",
            "-y, --yes",
            "--offline",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given plan command - when executed with --dir - then resolves workspace directory")
    void testDirectoryOption() {
        PlanCommand command = new PlanCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "-y", "--offline", "Draft architecture");

        assertThat(exitCode).isZero();
        assertThat(command.getStory()).isEqualTo("Draft architecture");
        assertThat(command.isYes()).isTrue();
        assertThat(command.isOffline()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }
}
