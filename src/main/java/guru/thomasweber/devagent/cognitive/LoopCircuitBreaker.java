package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.CircuitBreakerState;
import guru.thomasweber.devagent.model.LoopAction;
import guru.thomasweber.devagent.workspace.SubprocessExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Normalized loop circuit breaker governing the TDD coder loop (ADR-009).
 * Detects repeating compiler errors, oscillating regression cycles, and trips
 * on exhaustion after failing to recover post-pivot.
 * Scopes state machine tracking to isolated task IDs or worktree paths.
 */
@ApplicationScoped
public class LoopCircuitBreaker {

    public static final int MAX_POST_PIVOT_FAILURES = 3;
    public static final int MAX_HISTORY_SIZE = 50;
    public static final Duration ROLLBACK_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_TASK_ID = "default";
    private static final String WORKTREE_PATH_SEGMENT = "/dev-agent-worktrees/";

    private final ErrorSignatureExtractor signatureExtractor;
    private final SubprocessExecutor subprocessExecutor;

    private static class TaskBreakerState {
        CircuitBreakerState state = CircuitBreakerState.NORMAL;
        final LinkedList<String> history = new LinkedList<>();
        int postPivotFailures = 0;
    }

    private final ConcurrentMap<String, TaskBreakerState> taskStates = new ConcurrentHashMap<>();

    @Inject
    public LoopCircuitBreaker(ErrorSignatureExtractor signatureExtractor, SubprocessExecutor subprocessExecutor) {
        this.signatureExtractor = signatureExtractor;
        this.subprocessExecutor = subprocessExecutor;
    }

    public synchronized LoopAction recordFailure(String rawOutput) {
        return recordFailure(DEFAULT_TASK_ID, rawOutput, null);
    }

    public synchronized LoopAction recordFailure(String rawOutput, Path workingDir) {
        String taskId = resolveTaskId(workingDir);
        return recordFailure(taskId, rawOutput, workingDir);
    }

    public synchronized LoopAction recordFailure(String taskId, String rawOutput, Path workingDir) {
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.computeIfAbsent(effectiveTaskId, _ -> new TaskBreakerState());
        String sig = signatureExtractor.extractSignature(rawOutput);

        if (taskState.state == CircuitBreakerState.PIVOT_ACTIVE) {
            taskState.postPivotFailures++;
            if (taskState.postPivotFailures >= MAX_POST_PIVOT_FAILURES) {
                taskState.state = CircuitBreakerState.TRIPPED;
                // Preserve diagnostic error state on trip; do not rollback!
                return LoopAction.TRIP_AND_HALT;
            }
            appendHistory(taskState, sig);
            return LoopAction.CONTINUE;
        }

        if (!taskState.history.isEmpty()) {
            String lastSig = taskState.history.getLast();
            if (Objects.equals(lastSig, sig)) {
                taskState.state = CircuitBreakerState.WARNING;
                appendHistory(taskState, sig);
                return LoopAction.INJECT_WARNING;
            }

            int firstIndex = taskState.history.indexOf(sig);
            if (firstIndex >= 0) {
                taskState.state = CircuitBreakerState.PIVOT_ACTIVE;
                taskState.postPivotFailures = 0;
                appendHistory(taskState, sig);
                rollbackIfPossible(workingDir);
                return LoopAction.ROLLBACK_AND_PIVOT;
            }
        }

        taskState.state = CircuitBreakerState.NORMAL;
        appendHistory(taskState, sig);
        return LoopAction.CONTINUE;
    }

    private void appendHistory(TaskBreakerState taskState, String sig) {
        taskState.history.add(sig);
        if (taskState.history.size() > MAX_HISTORY_SIZE) {
            taskState.history.removeFirst();
        }
    }

    public synchronized void recordSuccess() {
        recordSuccess(DEFAULT_TASK_ID);
    }

    public synchronized void recordSuccess(String taskId) {
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.get(effectiveTaskId);
        if (taskState != null) {
            taskState.state = CircuitBreakerState.NORMAL;
            taskState.history.clear();
            taskState.postPivotFailures = 0;
        }
    }

    public synchronized void reset() {
        taskStates.clear();
    }

    public synchronized void reset(String taskId) {
        recordSuccess(taskId);
    }

    public synchronized CircuitBreakerState getState() {
        return getState(DEFAULT_TASK_ID);
    }

    public synchronized CircuitBreakerState getState(String taskId) {
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.get(effectiveTaskId);
        return taskState != null ? taskState.state : CircuitBreakerState.NORMAL;
    }

    public synchronized int getPostPivotFailures() {
        return getPostPivotFailures(DEFAULT_TASK_ID);
    }

    public synchronized int getPostPivotFailures(String taskId) {
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.get(effectiveTaskId);
        return taskState != null ? taskState.postPivotFailures : 0;
    }

    private void rollbackIfPossible(Path workingDir) {
        if (workingDir == null) {
            return;
        }
        String normalized = workingDir.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (!normalized.contains(WORKTREE_PATH_SEGMENT)) {
            throw new SecurityException("Refusing to rollback outside dedicated git worktree: " + workingDir);
        }
        subprocessExecutor.execute(List.of("git", "reset", "--hard"), workingDir, ROLLBACK_TIMEOUT);
    }

    private String resolveTaskId(Path workingDir) {
        if (workingDir == null) {
            return DEFAULT_TASK_ID;
        }
        String normalized = workingDir.toAbsolutePath().normalize().toString().replace('\\', '/');
        int idx = normalized.indexOf(WORKTREE_PATH_SEGMENT);
        if (idx >= 0) {
            String remainder = normalized.substring(idx + WORKTREE_PATH_SEGMENT.length());
            int slashIdx = remainder.indexOf('/');
            return slashIdx >= 0 ? remainder.substring(0, slashIdx) : remainder;
        }
        return DEFAULT_TASK_ID;
    }
}
