package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.isNull;

/**
 * Strongly-typed immutable identity and lifecycle handle for an OS worker process.
 * Guards against OS PID recycling via start-timestamp comparison and encapsulates
 * graceful-then-forcible process termination.
 */
@RegisterForReflection
public record WorkerProcess(long pid, Instant startedAt) {

    private static final long PID_START_TOLERANCE_SECONDS = 2L;
    private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofMillis(100);

    public WorkerProcess {
        if (pid <= 0) {
            throw new IllegalArgumentException("PID must be positive: " + pid);
        }
    }

    public static WorkerProcess current() {
        return of(ProcessHandle.current().pid());
    }

    public static WorkerProcess of(long pid) {
        return new WorkerProcess(pid, resolveStartInstant(pid));
    }

    public static WorkerProcess of(long pid, Instant startedAt) {
        return new WorkerProcess(pid, startedAt);
    }

    public static Optional<WorkerProcess> from(Long pid, Instant startedAt) {
        if (isNull(pid) || pid <= 0) {
            return Optional.empty();
        }
        return Optional.of(new WorkerProcess(pid, startedAt));
    }

    public static Optional<WorkerProcess> fromState(WorktreeState state) {
        if (isNull(state)) {
            return Optional.empty();
        }
        return from(state.pid(), state.pidStartedAt());
    }

    public boolean isAlive() {
        return resolveAliveHandle().isPresent();
    }

    public boolean matches(ProcessHandle handle) {
        if (isNull(handle) || !handle.isAlive() || handle.pid() != pid) {
            return false;
        }
        if (isNull(startedAt)) {
            return true;
        }
        Optional<Instant> actualStart = handle.info().startInstant();
        if (actualStart.isEmpty()) {
            return true;
        }
        return Duration.between(startedAt, actualStart.get()).abs().toSeconds() <= PID_START_TOLERANCE_SECONDS;
    }

    public void terminateGracefully(Duration gracePeriod) {
        if (pid == ProcessHandle.current().pid()) {
            return;
        }
        long waitMs = Math.max(1L, Objects.requireNonNullElse(gracePeriod, DEFAULT_GRACE_PERIOD).toMillis());
        resolveAliveHandle().ifPresent(handle -> {
            handle.destroy();
            try {
                handle.onExit().get(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException _) {
                // Process still alive after grace period; fall through to destroyForcibly
            }
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
    }

    private Optional<ProcessHandle> resolveAliveHandle() {
        return ProcessHandle.of(pid).filter(this::matches);
    }

    private static Instant resolveStartInstant(long pid) {
        return ProcessHandle.of(pid)
            .flatMap(handle -> handle.info().startInstant())
            .orElse(null);
    }
}
