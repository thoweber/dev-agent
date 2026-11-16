package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.CircuitBreakerState;
import guru.thomasweber.devagent.model.LoopAction;
import guru.thomasweber.devagent.workspace.ProcessResult;
import guru.thomasweber.devagent.workspace.SubprocessExecutor;
import guru.thomasweber.devagent.workspace.WorktreeLayout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

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

    private final ErrorSignatureExtractor signatureExtractor;
    private final SubprocessExecutor subprocessExecutor;

    private static class TaskBreakerState {
        CircuitBreakerState state = CircuitBreakerState.NORMAL;
        final List<String> history = new ArrayList<>();
        int postPivotFailures = 0;
        int consecutiveRepeatCount = 0;
    }

    private final ConcurrentMap<String, TaskBreakerState> taskStates = new ConcurrentHashMap<>();

    @Inject
    public LoopCircuitBreaker(ErrorSignatureExtractor signatureExtractor, SubprocessExecutor subprocessExecutor) {
        this.signatureExtractor = signatureExtractor;
        this.subprocessExecutor = subprocessExecutor;
    }

    public LoopAction recordFailure(String rawOutput) {
        return recordFailure(DEFAULT_TASK_ID, rawOutput, null);
    }

    public LoopAction recordFailure(String rawOutput, Path workingDir) {
        String taskId = resolveTaskId(workingDir);
        return recordFailure(taskId, rawOutput, workingDir);
    }

    public LoopAction recordFailure(String taskId, String rawOutput, Path workingDir) {
        String effectiveTaskId = normalizeTaskId(taskId);
        TaskBreakerState taskState = taskStates.computeIfAbsent(effectiveTaskId, _ -> new TaskBreakerState());
        String sig = signatureExtractor.extractSignature(rawOutput);

        LockDecision decision;
        synchronized (taskState) {
            decision = evaluateFailureUnderLock(taskState, sig);
        }
        switch (decision) {
            case TRIP_AND_HALT -> {
                return LoopAction.TRIP_AND_HALT;
            }
            case INJECT_WARNING -> {
                return LoopAction.INJECT_WARNING;
            }
            case CONTINUE -> {
                return LoopAction.CONTINUE;
            }
            case NEEDS_ROLLBACK -> {
                // Proceed outside the synchronized monitor before invoking subprocess rollback
            }
        }

        boolean rolledBack = rollback(workingDir);
        synchronized (taskState) {
            if (rolledBack) {
                taskState.state = CircuitBreakerState.PIVOT_ACTIVE;
                taskState.postPivotFailures = 0;
                taskState.consecutiveRepeatCount = 0;
                return LoopAction.ROLLBACK_AND_PIVOT;
            }
            taskState.state = CircuitBreakerState.TRIPPED;
            return LoopAction.TRIP_AND_HALT;
        }
    }

    private enum LockDecision {
        CONTINUE,
        INJECT_WARNING,
        NEEDS_ROLLBACK,
        TRIP_AND_HALT
    }

    private LockDecision evaluateFailureUnderLock(TaskBreakerState taskState, String sig) {
        if (taskState.state == CircuitBreakerState.TRIPPED) {
            return LockDecision.TRIP_AND_HALT;
        }
        if (taskState.state == CircuitBreakerState.PIVOT_ACTIVE) {
            return evaluatePostPivotUnderLock(taskState, sig);
        }
        if (!taskState.history.isEmpty() && Objects.equals(taskState.history.getLast(), sig)) {
            taskState.consecutiveRepeatCount++;
            appendHistory(taskState, sig);
            if (taskState.consecutiveRepeatCount < 2) {
                taskState.state = CircuitBreakerState.WARNING;
                return LockDecision.INJECT_WARNING;
            }
            return LockDecision.NEEDS_ROLLBACK;
        }
        taskState.consecutiveRepeatCount = 0;
        boolean oscillating = !taskState.history.isEmpty() && isOscillatingSequence(taskState.history, sig);
        appendHistory(taskState, sig);
        if (oscillating) {
            return LockDecision.NEEDS_ROLLBACK;
        }
        taskState.state = CircuitBreakerState.NORMAL;
        return LockDecision.CONTINUE;
    }

    private LockDecision evaluatePostPivotUnderLock(TaskBreakerState taskState, String sig) {
        taskState.postPivotFailures++;
        if (taskState.postPivotFailures >= MAX_POST_PIVOT_FAILURES) {
            taskState.state = CircuitBreakerState.TRIPPED;
            return LockDecision.TRIP_AND_HALT;
        }
        appendHistory(taskState, sig);
        return LockDecision.CONTINUE;
    }

    private boolean isOscillatingSequence(List<String> history, String currentSig) {
        int size = history.size();
        if (size < 3) {
            return false;
        }
        String a1 = history.get(size - 3);
        String b1 = history.get(size - 2);
        String a2 = history.get(size - 1);
        String b2 = currentSig;
        return Objects.equals(a1, a2) && Objects.equals(b1, b2) && !Objects.equals(a1, b1);
    }

    private void appendHistory(TaskBreakerState taskState, String sig) {
        taskState.history.add(sig);
        if (taskState.history.size() > MAX_HISTORY_SIZE) {
            taskState.history.removeFirst();
        }
    }

    public void recordSuccess() {
        recordSuccess(DEFAULT_TASK_ID);
    }

    public void recordSuccess(String taskId) {
        taskStates.remove(normalizeTaskId(taskId));
    }

    public void reset() {
        taskStates.clear();
    }

    public void reset(String taskId) {
        recordSuccess(taskId);
    }

    public CircuitBreakerState getState() {
        return getState(DEFAULT_TASK_ID);
    }

    public CircuitBreakerState getState(String taskId) {
        TaskBreakerState taskState = taskStates.get(normalizeTaskId(taskId));
        if (nonNull(taskState)) {
            synchronized (taskState) {
                return taskState.state;
            }
        }
        return CircuitBreakerState.NORMAL;
    }

    public int getPostPivotFailures() {
        return getPostPivotFailures(DEFAULT_TASK_ID);
    }

    public int getPostPivotFailures(String taskId) {
        TaskBreakerState taskState = taskStates.get(normalizeTaskId(taskId));
        if (nonNull(taskState)) {
            synchronized (taskState) {
                return taskState.postPivotFailures;
            }
        }
        return 0;
    }

    private static String normalizeTaskId(String taskId) {
        if (nonNull(taskId) && !taskId.isBlank()) {
            return taskId;
        }
        return DEFAULT_TASK_ID;
    }

    private boolean rollback(Path workingDir) {
        if (isNull(workingDir)) {
            return false;
        }
        Path worktreeRoot = WorktreeLayout.detectWorktreeRoot(workingDir)
                .orElseThrow(() -> new SecurityException("Refusing to rollback outside dedicated git worktree: " + workingDir));
        ProcessResult resetResult = subprocessExecutor.execute(List.of("git", "reset", "--hard"), worktreeRoot, ROLLBACK_TIMEOUT);
        if (!resetResult.isSuccess()) {
            return false;
        }
        ProcessResult cleanResult = subprocessExecutor.execute(List.of("git", "clean", "-fd"), worktreeRoot, ROLLBACK_TIMEOUT);
        return cleanResult.isSuccess();
    }

    private String resolveTaskId(Path workingDir) {
        return findWorktreeTaskId(workingDir).orElse(DEFAULT_TASK_ID);
    }

    private Optional<String> findWorktreeTaskId(Path workingDir) {
        return WorktreeLayout.detectWorktreeRoot(workingDir)
                .map(root -> root.getFileName().toString());
    }
}
