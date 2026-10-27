package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubprocessExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid command - when executed - captures exit code and stdout")
    void testExecuteSimpleCommand() {
        SubprocessExecutor executor = new SubprocessExecutor();
        ProcessResult result = executor.execute(
            List.of("echo", "dev-agent-subprocess-test"),
            tempDir,
            Duration.ofSeconds(5)
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdOut().trim()).isEqualTo("dev-agent-subprocess-test");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("Given command with custom environment - propagates environment variables")
    void testExecuteWithCustomEnvironment() {
        SubprocessExecutor executor = new SubprocessExecutor();
        ProcessResult result = executor.execute(
            List.of("sh", "-c", "echo $DEV_AGENT_TEST_VAR"),
            tempDir,
            Duration.ofSeconds(5),
            Map.of("DEV_AGENT_TEST_VAR", "injected_secret_value")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdOut().trim()).isEqualTo("injected_secret_value");
    }

    @Test
    @DisplayName("Given long running command exceeding timeout - terminates process forcibly and flags timeout")
    void testExecuteTimeoutKillsProcessForcibly() {
        SubprocessExecutor executor = new SubprocessExecutor();
        long start = System.currentTimeMillis();

        ProcessResult result = executor.execute(
            List.of("sleep", "10"),
            tempDir,
            Duration.ofMillis(200)
        );

        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.timedOut()).isTrue();
        assertThat(elapsed).isLessThan(2000); // Forcibly killed well before 10 seconds
    }

    @Test
    @DisplayName("Given failing command - captures stderr and exit code")
    void testExecuteFailingCommand() {
        SubprocessExecutor executor = new SubprocessExecutor();
        ProcessResult result = executor.execute(
            List.of("ls", "non_existent_file_xyz_123"),
            tempDir,
            Duration.ofSeconds(5)
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdErr()).isNotEmpty();
    }
}
