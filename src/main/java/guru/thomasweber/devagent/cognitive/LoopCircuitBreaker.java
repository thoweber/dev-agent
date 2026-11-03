package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.CircuitBreakerState;
import guru.thomasweber.devagent.model.LoopAction;
import guru.thomasweber.devagent.workspace.ProcessResult;
import guru.thomasweber.devagent.workspace.SubprocessExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private static final String GIT_DIR_NAME = ".git";
    private static final String WORKTREE_DIR_NAME = "dev-agent-worktrees";

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

    public LoopAction recordFailure(String rawOutput) {
        return recordFailure(DEFAULT_TASK_ID, rawOutput, null);
    }

    public LoopAction recordFailure(String rawOutput, Path workingDir) {
        String taskId = resolveTaskId(workingDir);
        return recordFailure(taskId, rawOutput, workingDir);
    }

    public LoopAction recordFailure(String taskId, String rawOutput, Path workingDir) {
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.computeIfAbsent(effectiveTaskId, _ -> new TaskBreakerState());
        String sig = signatureExtractor.extractSignature(rawOutput);

        synchronized (taskState) {
            if (taskState.state == CircuitBreakerState.TRIPPED) {
                return LoopAction.TRIP_AND_HALT;
            }

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

                if (isOscillatingSequence(taskState.history, sig)) {
                    appendHistory(taskState, sig);
                    boolean rolledBack = rollback(workingDir);
                    if (rolledBack) {
                        taskState.state = CircuitBreakerState.PIVOT_ACTIVE;
                        taskState.postPivotFailures = 0;
                        return LoopAction.ROLLBACK_AND_PIVOT;
                    } else {
                        taskState.state = CircuitBreakerState.TRIPPED;
                        return LoopAction.TRIP_AND_HALT;
                    }
                }
            }

            taskState.state = CircuitBreakerState.NORMAL;
            appendHistory(taskState, sig);
            return LoopAction.CONTINUE;
        }
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
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.get(effectiveTaskId);
        if (taskState != null) {
            synchronized (taskState) {
                taskState.state = CircuitBreakerState.NORMAL;
                taskState.history.clear();
                taskState.postPivotFailures = 0;
            }
        }
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
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.get(effectiveTaskId);
        if (taskState != null) {
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
        String effectiveTaskId = (taskId != null && !taskId.isBlank()) ? taskId : DEFAULT_TASK_ID;
        TaskBreakerState taskState = taskStates.get(effectiveTaskId);
        if (taskState != null) {
            synchronized (taskState) {
                return taskState.postPivotFailures;
            }
        }
        return 0;
    }

    private boolean rollback(Path workingDir) {
        if (workingDir == null) {
            return false;
        }
        Path worktreeRoot = findWorktreeRoot(workingDir)
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
        return findWorktreeRoot(workingDir)
                .map(root -> root.getFileName().toString());
    }

    private Optional<Path> findWorktreeRoot(Path workingDir) {
        if (workingDir == null) {
            return Optional.empty();
        }
        Path normalized = workingDir.toAbsolutePath().normalize();
        for (int i = 0; i < normalized.getNameCount() - 2; i++) {
            if (GIT_DIR_NAME.equals(normalized.getName(i).toString())
                    && WORKTREE_DIR_NAME.equals(normalized.getName(i + 1).toString())) {
                Path root = normalized.getRoot();
                Path subpath = normalized.subpath(0, i + 3);
                Path worktreeRoot = root != null ? root.resolve(subpath) : subpath;
                validateNoSymbolicLinks(worktreeRoot);
                return Optional.of(worktreeRoot);
            }
        }
        return Optional.empty();
    }

    private void validateNoSymbolicLinks(Path path) {
        Path curr = path;
        while (curr != null) {
            if (Files.isSymbolicLink(curr)) {
                throw new SecurityException("Forbidden symbolic link in worktree path: " + path);
            }
            curr = curr.getParent();
        }
    }
}
