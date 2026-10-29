package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(SubprocessExecutor.class);

    public static final int DEFAULT_MEMORY_THRESHOLD = 1024 * 1024;       // 1 MB
    public static final int DEFAULT_MAX_CAPACITY = 10 * 1024 * 1024;     // 10 MB

    private final int memoryThreshold;
    private final int maxCapacity;

    public SubprocessExecutor() {
        this(DEFAULT_MEMORY_THRESHOLD, DEFAULT_MAX_CAPACITY);
    }

    public SubprocessExecutor(int memoryThreshold, int maxCapacity) {
        this.memoryThreshold = memoryThreshold;
        this.maxCapacity = maxCapacity;
    }

    public ProcessResult execute(List<String> command, Path workingDir, Duration timeout) {
        return execute(command, workingDir, timeout, Map.of());
    }

    public ProcessResult execute(
        List<String> command,
        Path workingDir,
        Duration timeout,
        Map<String, String> extraEnv
    ) {
        ProcessBuilder pb = createProcessBuilder(command, workingDir, extraEnv);
        Process process;
        try {
            process = pb.start();
            closeProcessStdin(process);
        } catch (IOException e) {
            LOG.warnf(e, "Subprocess execution failed: %s", e.getMessage());
            return failureResult(e.getMessage());
        }

        try (
            BoundedOverflowBuffer stdoutBuffer = new BoundedOverflowBuffer(memoryThreshold, maxCapacity);
            BoundedOverflowBuffer stderrBuffer = new BoundedOverflowBuffer(memoryThreshold, maxCapacity)
        ) {
            Thread stdoutDrainer = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdoutBuffer.outputStream()));
            Thread stderrDrainer = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderrBuffer.outputStream()));

            boolean timedOut = waitForProcess(process, timeout);
            joinDrainers(stdoutDrainer, stderrDrainer);

            String out = readOutputSafely(stdoutBuffer);
            String err = readOutputSafely(stderrBuffer);
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
            return failureResult("Failed to allocate process output buffers");
        }
    }

    private ProcessBuilder createProcessBuilder(List<String> command, Path workingDir, Map<String, String> extraEnv) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        return pb;
    }

    private void closeProcessStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            LOG.debugf(e, "Could not close process stdin: %s", e.getMessage());
        }
    }

    private boolean waitForProcess(Process process, Duration timeout) {
        boolean finished = false;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            killProcessTree(process);
            Thread.currentThread().interrupt();
            return true;
        }

        if (!finished) {
            killProcessTree(process);
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
        return false;
    }

    private void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void joinDrainers(Thread stdoutDrainer, Thread stderrDrainer) {
        try {
            stdoutDrainer.join(Duration.ofSeconds(1));
            stderrDrainer.join(Duration.ofSeconds(1));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private String readOutputSafely(BoundedOverflowBuffer buffer) {
        try {
            return buffer.asString(StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }

    private ProcessResult failureResult(String error) {
        return ProcessResult.builder()
            .exitCode(-1)
            .stdOut("")
            .stdErr(error)
            .timedOut(false)
            .truncated(false)
            .build();
    }

    private void drain(InputStream source, OutputStream sink) {
        try {
            source.transferTo(sink);
        } catch (IOException _) {
            // Process terminated or stream closed
        }
    }
}
