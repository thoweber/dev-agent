package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given feature command with instruction and flags - when parsed - then sets properties correctly")
    void testFeatureCommandFlags() {
        FeatureCommand command = new FeatureCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute(
            "--dir=" + tempDir.toString(),
            "--gui",
            "--detach",
            "--yes",
            "--offline",
            "Add User Metrics API"
        );

        assertThat(exitCode).isZero();
        assertThat(command.isGui()).isTrue();
        assertThat(command.isDetach()).isTrue();
        assertThat(command.isYes()).isTrue();
        assertThat(command.isOffline()).isTrue();
        assertThat(command.getInstruction()).isEqualTo("Add User Metrics API");
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given feature command without instruction - when executed - then returns warning message")
    void testFeatureCommandWithoutInstruction() {
        FeatureCommand command = new FeatureCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("Please provide a feature specification");

        StringWriter swBlank = new StringWriter();
        cmd.setOut(new PrintWriter(swBlank));
        int exitCodeBlank = cmd.execute("   ");
        assertThat(exitCodeBlank).isZero();
        assertThat(swBlank.toString()).contains("Please provide a feature specification");
    }
}
