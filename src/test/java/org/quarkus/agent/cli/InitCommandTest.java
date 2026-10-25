package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InitCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given init command with default type - when executed - then scaffolds rest-panache archetype")
    void testInitDefaultArchetype() {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir.toString());

        assertThat(exitCode).isZero();
        assertThat(tempDir.resolve(".agents")).isDirectory();
        assertThat(tempDir.resolve(".agents/dev-agent.yaml")).isRegularFile();
        assertThat(tempDir.resolve(".agents/rules")).isDirectory();
    }

    @Test
    @DisplayName("Given init command with --type=reactive-messaging - when executed - then scaffolds reactive rules")
    void testInitReactiveMessaging() throws Exception {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--type=reactive-messaging");

        assertThat(exitCode).isZero();
        Path yamlPath = tempDir.resolve(".agents/dev-agent.yaml");
        assertThat(yamlPath).isRegularFile();
        String content = Files.readString(yamlPath);
        assertThat(content).contains("reactive-messaging");
    }

    @Test
    @DisplayName("Given init command with --type=fullstack-angular - when executed - then scaffolds frontend rules")
    void testInitFullstackAngular() throws Exception {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--type=fullstack-angular");

        assertThat(exitCode).isZero();
        Path yamlPath = tempDir.resolve(".agents/dev-agent.yaml");
        assertThat(yamlPath).isRegularFile();
        String content = Files.readString(yamlPath);
        assertThat(content).contains("fullstack-angular");
    }

    @Test
    @DisplayName("Given init command with --type=modulith - when executed - then scaffolds modulith rules")
    void testInitModulith() throws Exception {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--type=modulith");

        assertThat(exitCode).isZero();
        Path yamlPath = tempDir.resolve(".agents/dev-agent.yaml");
        assertThat(yamlPath).isRegularFile();
        String content = Files.readString(yamlPath);
        assertThat(content).contains("modulith");
    }

    @Test
    @DisplayName("Given init command with invalid type - when executed - then returns non-zero error")
    void testInitInvalidType() {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter err = new StringWriter();
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--type=unknown-archetype");

        assertThat(exitCode).isNotZero();
    }
}
