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

/**
 * Normalized loop circuit breaker governing the TDD coder loop (ADR-009).
 * Detects repeating compiler errors, oscillating regression cycles, and trips
 * on exhaustion after failing to recover post-pivot.
 */
@ApplicationScoped
public class LoopCircuitBreaker {

    public static final int MAX_POST_PIVOT_FAILURES = 3;
    public static final Duration ROLLBACK_TIMEOUT = Duration.ofSeconds(10);

    private final ErrorSignatureExtractor signatureExtractor;
    private final SubprocessExecutor subprocessExecutor;

    private CircuitBreakerState state = CircuitBreakerState.NORMAL;
    private final LinkedList<String> history = new LinkedList<>();
    private int postPivotFailures = 0;

    @Inject
    public LoopCircuitBreaker(ErrorSignatureExtractor signatureExtractor, SubprocessExecutor subprocessExecutor) {
        this.signatureExtractor = signatureExtractor;
        this.subprocessExecutor = subprocessExecutor;
    }

    public synchronized LoopAction recordFailure(String rawOutput) {
        return recordFailure(rawOutput, null);
    }

    public synchronized LoopAction recordFailure(String rawOutput, Path workingDir) {
        String sig = signatureExtractor.extractSignature(rawOutput);

        if (state == CircuitBreakerState.PIVOT_ACTIVE) {
            postPivotFailures++;
            if (postPivotFailures >= MAX_POST_PIVOT_FAILURES) {
                state = CircuitBreakerState.TRIPPED;
                rollbackIfPossible(workingDir);
                return LoopAction.TRIP_AND_HALT;
            }
            history.add(sig);
            return LoopAction.CONTINUE;
        }

        if (!history.isEmpty()) {
            String lastSig = history.getLast();
            if (Objects.equals(lastSig, sig)) {
                state = CircuitBreakerState.WARNING;
                history.add(sig);
                return LoopAction.INJECT_WARNING;
            }

            int firstIndex = history.indexOf(sig);
            if (firstIndex >= 0) {
                state = CircuitBreakerState.PIVOT_ACTIVE;
                postPivotFailures = 0;
                history.add(sig);
                rollbackIfPossible(workingDir);
                return LoopAction.ROLLBACK_AND_PIVOT;
            }
        }

        state = CircuitBreakerState.NORMAL;
        history.add(sig);
        return LoopAction.CONTINUE;
    }

    public synchronized void recordSuccess() {
        state = CircuitBreakerState.NORMAL;
        history.clear();
        postPivotFailures = 0;
    }

    public synchronized void reset() {
        recordSuccess();
    }

    public synchronized CircuitBreakerState getState() {
        return state;
    }

    public synchronized int getPostPivotFailures() {
        return postPivotFailures;
    }

    private void rollbackIfPossible(Path workingDir) {
        if (workingDir != null) {
            subprocessExecutor.execute(List.of("git", "reset", "--hard"), workingDir, ROLLBACK_TIMEOUT);
        }
    }
}
