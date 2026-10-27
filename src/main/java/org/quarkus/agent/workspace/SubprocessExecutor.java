package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Robust subprocess execution engine enforcing mandatory timeouts, forced process cleanup,
 * and bounded 2-tier output buffering (1 MB RAM -> 10 MB Disk overflow) to eliminate pipe deadlocks.
 */
@ApplicationScoped
public class SubprocessExecutor {

    public static final int DEFAULT_MEMORY_THRESHOLD = 1024 * 1024;       // 1 MB
    public static final int DEFAULT_MAX_CAPACITY = 10 * 1024 * 1024;     // 10 MB

    public ProcessResult execute(List<String> command, Path workingDir, Duration timeout) {
        return execute(command, workingDir, timeout, Map.of());
    }

    public ProcessResult execute(
        List<String> command,
        Path workingDir,
        Duration timeout,
        Map<String, String> extraEnv
    ) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }

        try (
            BoundedOverflowBuffer stdoutBuffer = new BoundedOverflowBuffer(DEFAULT_MEMORY_THRESHOLD, DEFAULT_MAX_CAPACITY);
            BoundedOverflowBuffer stderrBuffer = new BoundedOverflowBuffer(DEFAULT_MEMORY_THRESHOLD, DEFAULT_MAX_CAPACITY)
        ) {
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                return ProcessResult.builder()
                    .exitCode(-1)
                    .stdOut("")
                    .stdErr(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .timedOut(false)
                    .truncated(false)
                    .build();
            }

            Thread stdoutDrainer = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdoutBuffer.outputStream()));
            Thread stderrDrainer = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderrBuffer.outputStream()));

            boolean finished = false;
            boolean timedOut = false;

            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                timedOut = true;
            }

            if (!finished) {
                timedOut = true;
                process.destroyForcibly();
                try {
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }

            try {
                stdoutDrainer.join(Duration.ofSeconds(1));
                stderrDrainer.join(Duration.ofSeconds(1));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }

            String out = "";
            String err = "";
            try {
                out = stdoutBuffer.asString(StandardCharsets.UTF_8);
                err = stderrBuffer.asString(StandardCharsets.UTF_8);
            } catch (IOException _) {
                // Buffers closed or read failed
            }

            int exitCode = timedOut ? -1 : process.exitValue();
            boolean truncated = stdoutBuffer.isTruncated() || stderrBuffer.isTruncated();

            return ProcessResult.builder()
                .exitCode(exitCode)
                .stdOut(out)
                .stdErr(err)
                .timedOut(timedOut)
                .truncated(truncated)
                .build();

        } catch (IOException _) {
            return ProcessResult.builder()
                .exitCode(-1)
                .stdOut("")
                .stdErr("Failed to allocate process output buffers")
                .timedOut(false)
                .truncated(false)
                .build();
        }
    }

    private void drain(InputStream source, OutputStream sink) {
        try {
            source.transferTo(sink);
        } catch (IOException _) {
            // Process terminated or stream closed
        }
    }
}
