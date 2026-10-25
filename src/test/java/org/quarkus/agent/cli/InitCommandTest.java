package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkus.agent.DevAgentCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reactive-messaging", "fullstack-angular", "modulith"})
    @DisplayName("Given init command with archetype profile - then scaffolds matching configuration")
    void testInitArchetypes(String archetypeId) throws Exception {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--type=" + archetypeId, "--force");

        assertThat(exitCode).isZero();
        Path yamlPath = tempDir.resolve(".agents/dev-agent.yaml");
        assertThat(yamlPath).isRegularFile();
        String content = Files.readString(yamlPath);
        assertThat(content).contains(archetypeId);
    }

    @Test
    @DisplayName("Given existing configuration with different archetype - when executed without force - then rejects change")
    void testInitExistingArchetypeMismatchWithoutForce() throws Exception {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter err = new StringWriter();
        cmd.setErr(new PrintWriter(err));

        int exit1 = cmd.execute("--dir=" + tempDir.toString(), "--type=rest-panache");
        assertThat(exit1).isZero();

        int exit2 = cmd.execute("--dir=" + tempDir.toString(), "--type=reactive-messaging");
        assertThat(exit2).isZero();
        assertThat(err.toString()).contains("Workspace already initialized with different archetype. Use --force to overwrite.");

        Path yamlPath = tempDir.resolve(".agents/dev-agent.yaml");
        String content = Files.readString(yamlPath);
        assertThat(content).contains("rest-panache").doesNotContain("reactive-messaging");
    }

    @Test
    @DisplayName("Given existing configuration - when executed with force - then overwrites archetype")
    void testInitExistingArchetypeWithForce() throws Exception {
        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);

        int exit1 = cmd.execute("--dir=" + tempDir.toString(), "--type=rest-panache");
        assertThat(exit1).isZero();

        int exit2 = cmd.execute("--dir=" + tempDir.toString(), "--type=reactive-messaging", "--force");
        assertThat(exit2).isZero();

        Path yamlPath = tempDir.resolve(".agents/dev-agent.yaml");
        String content = Files.readString(yamlPath);
        assertThat(content).contains("reactive-messaging");
    }

    @Test
    @DisplayName("Given symbolic link at yamlPath - when init executes - then rejects writing with SecurityException")
    void testInitRejectsSymbolicLink() throws Exception {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Path targetFile = tempDir.resolve("target.yaml");
        Files.writeString(targetFile, "dummy");
        Path symlinkYaml = agentsDir.resolve("dev-agent.yaml");
        Files.createSymbolicLink(symlinkYaml, targetFile);

        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);
        cmd.parseArgs("--dir=" + tempDir.toString(), "--force");

        assertThatThrownBy(command::run)
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Refusing to write through symbolic link");

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--force");
        assertThat(exitCode).isNotZero();
    }

    @Test
    @DisplayName("Given ArchetypeType enum - when queried - returns expected properties")
    void testArchetypeTypeEnum() {
        assertThat(ArchetypeType.REST_PANACHE.getId()).isEqualTo("rest-panache");
        assertThat(ArchetypeType.REACTIVE_MESSAGING.getId()).isEqualTo("reactive-messaging");
        assertThat(ArchetypeType.FULLSTACK_ANGULAR.getId()).isEqualTo("fullstack-angular");
        assertThat(ArchetypeType.MODULITH.getId()).isEqualTo("modulith");

        assertThat(ArchetypeType.fromString(null)).isEqualTo(ArchetypeType.REST_PANACHE);
        assertThat(ArchetypeType.fromString("   ")).isEqualTo(ArchetypeType.REST_PANACHE);
        assertThat(ArchetypeType.fromString("modulith")).isEqualTo(ArchetypeType.MODULITH);
        assertThat(ArchetypeType.fromString("MODULITH")).isEqualTo(ArchetypeType.MODULITH);
    }

    @Test
    @DisplayName("Given root dev-agent command with --dir - when init subcommand runs - then inherits workspace dir")
    void testInitInheritsDirFromRoot() {
        DevAgentCommand root = new DevAgentCommand();
        CommandLine cmd = new CommandLine(root);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "init");

        assertThat(exitCode).isZero();
        assertThat(tempDir.resolve(".agents/dev-agent.yaml")).isRegularFile();
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

    @Test
    @DisplayName("Given subdirectory within existing repo - when init executed with --dir - scaffolds strictly within subdirectory")
    void testInitSubdirectoryWithinRepo() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        Path subDir = Files.createDirectories(tempDir.resolve("sub-module"));

        InitCommand command = new InitCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + subDir.toString());

        assertThat(exitCode).isZero();
        assertThat(subDir.resolve(".agents/dev-agent.yaml")).isRegularFile();
        assertThat(tempDir.resolve(".agents")).doesNotExist();
    }
}
