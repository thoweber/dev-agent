package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Stateless factory creating per-CLI-invocation concurrency gates ({@link PermitGate})
 * bounded by {@code agent.max-concurrent-workers} from {@code .agents/dev-agent.yaml} (default 2).
 */
@ApplicationScoped
public class LocalWorkerLimiter {

    public static final int DEFAULT_MAX_CONCURRENT_WORKERS = 2;

    private final StateSocketService socketService;
    private final DevAgentConfigLoader configLoader;

    @Inject
    public LocalWorkerLimiter(StateSocketService socketService, DevAgentConfigLoader configLoader) {
        this.socketService = socketService;
        this.configLoader = configLoader;
    }

    public LocalWorkerLimiter() {
        this(new StateSocketService(), new DevAgentConfigLoader());
    }

    public PermitGate openGate(int maxConcurrentWorkers) {
        int bounded = Math.max(1, maxConcurrentWorkers);
        return new PermitGate(bounded, socketService);
    }

    public PermitGate openGateForWorkspace(Path repoRoot) {
        int configured = resolveConfiguredLimit(repoRoot);
        return openGate(configured);
    }

    private int resolveConfiguredLimit(Path repoRoot) {
        return configLoader.loadForWorkspace(repoRoot).maxConcurrentWorkers();
    }

    /**
     * Task paired with its initial {@link WorktreeState} for bounded concurrent execution.
     */
    public record QueuedTask<T>(WorktreeState state, Callable<T> work) {}

    /**
     * Per-invocation concurrency gate enforcing {@code maxPermits} concurrent tasks.
     */
    public static final class PermitGate {

        private final int maxPermits;
        private final Semaphore semaphore;
        private final AtomicInteger queuedCount;
        private final StateSocketService socketService;

        private PermitGate(int maxPermits, StateSocketService socketService) {
            this.maxPermits = maxPermits;
            this.semaphore = new Semaphore(maxPermits, true);
            this.queuedCount = new AtomicInteger(0);
            this.socketService = socketService;
        }

        public <T> T executeWithPermit(Path repoRoot, WorktreeState candidateState, Callable<T> work) {
            boolean acquired = semaphore.tryAcquire();
            if (!acquired) {
                awaitQueuedPermit(repoRoot, candidateState);
            }
            try {
                return work.call();
            } catch (WorkspaceException e) {
                throw e;
            } catch (Exception e) {
                throw new WorkspaceException("Worker task execution failed", e);
            } finally {
                semaphore.release();
            }
        }

        public <T> List<T> executeBatch(Path repoRoot, List<QueuedTask<T>> tasks) {
            if (isNull(tasks) || tasks.isEmpty()) {
                return List.of();
            }
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (QueuedTask<T> task : tasks) {
                    futures.add(executor.submit(() -> executeWithPermit(repoRoot, task.state(), task.work())));
                }
                List<T> results = new ArrayList<>(tasks.size());
                for (Future<T> future : futures) {
                    results.add(future.get());
                }
                return List.copyOf(results);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkspaceException("Interrupted while executing worker batch", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof WorkspaceException we) {
                    throw we;
                }
                throw new WorkspaceException("Worker batch execution failed", cause);
            }
        }

        public int maxPermits() {
            return maxPermits;
        }

        public int availablePermits() {
            return semaphore.availablePermits();
        }

        public int queuedCount() {
            return queuedCount.get();
        }

        private void awaitQueuedPermit(Path repoRoot, WorktreeState candidateState) {
            if (nonNull(repoRoot) && nonNull(candidateState)) {
                WorktreeState queuedState = candidateState.toBuilder()
                    .status(TaskStatus.QUEUED)
                    .phase(TaskStatus.QUEUED.name())
                    .updatedAt(Instant.now())
                    .lastMessage("Waiting for available worker permit in current invocation")
                    .build();
                socketService.writeHeartbeat(repoRoot, queuedState);
            }
            queuedCount.incrementAndGet();
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkspaceException("Interrupted while waiting for worker permit", e);
            } finally {
                queuedCount.decrementAndGet();
            }
        }
    }
}
