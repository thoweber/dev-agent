package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // Null working dir and null / empty extraEnv
        ProcessResult resNullDir = executor.execute(List.of("echo", "null-dir"), null, Duration.ofSeconds(5), null);
        assertThat(resNullDir.isSuccess()).isTrue();

        ProcessResult resEmptyEnv = executor.execute(List.of("echo", "empty-env"), null, Duration.ofSeconds(5), Map.of());
        assertThat(resEmptyEnv.isSuccess()).isTrue();
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

    @Test
    @DisplayName("Given non-existent binary - returns failure result without hanging")
    void testExecuteNonExistentBinary() {
        SubprocessExecutor executor = new SubprocessExecutor();
        ProcessResult result = executor.execute(
            List.of("/bin/non_existent_binary_dev_agent_xyz_999"),
            null,
            Duration.ofSeconds(5),
            null
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.stdErr()).isNotEmpty();
    }

    @Test
    @DisplayName("Given thread interrupted during execution - terminates process and restores interrupt status")
    void testExecuteInterruptedThread() throws Exception {
        SubprocessExecutor executor = new SubprocessExecutor();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<ProcessResult> resultRef = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t = new Thread(() -> {
            ProcessResult res = executor.execute(
                List.of("sleep", "30"),
                tempDir,
                Duration.ofSeconds(30)
            );
            resultRef.set(res);
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        t.start();
        Thread.sleep(150);
        t.interrupt();
        t.join(2000);

        assertThat(interrupted.get()).isTrue();
        assertThat(resultRef.get()).isNotNull();
        assertThat(resultRef.get().timedOut()).isTrue();
    }

    @Test
    @DisplayName("Given command generating stdout exceeding max capacity - truncates stdout and flags truncated")
    void testExecuteStdoutTruncationFlag() {
        SubprocessExecutor executor = new SubprocessExecutor(64, 128);
        ProcessResult result = executor.execute(
            List.of("python3", "-c", "import sys; sys.stdout.write('A' * 256)"),
            tempDir,
            Duration.ofSeconds(5)
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdOut()).hasSize(128);
        assertThat(result.stdOut()).isEqualTo("A".repeat(128));
    }

    @Test
    @DisplayName("Given command generating stderr exceeding max capacity with empty stdout - truncates stderr and flags truncated")
    void testExecuteStderrTruncationFlag() {
        SubprocessExecutor executor = new SubprocessExecutor(64, 128);
        ProcessResult result = executor.execute(
            List.of("python3", "-c", "import sys; sys.stderr.write('B' * 256)"),
            tempDir,
            Duration.ofSeconds(5)
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdErr()).hasSize(128);
        assertThat(result.stdErr()).isEqualTo("B".repeat(128));
    }

    @Test
    @DisplayName("Given invalid constructor thresholds - when instantiated - throws IllegalArgumentException")
    void testConstructorValidations() {
        assertThatThrownBy(() -> new SubprocessExecutor(0, 100))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubprocessExecutor(100, 50))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
