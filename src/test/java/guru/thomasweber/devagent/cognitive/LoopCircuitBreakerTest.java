package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.CircuitBreakerState;
import guru.thomasweber.devagent.model.LoopAction;
import guru.thomasweber.devagent.workspace.ProcessResult;
import guru.thomasweber.devagent.workspace.SubprocessExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoopCircuitBreakerTest {

    @TempDir
    Path tempDir;

    private Path worktreeDir;
    private ErrorSignatureExtractor signatureExtractor;
    private SubprocessExecutor subprocessExecutor;
    private LoopCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() throws IOException {
        worktreeDir = tempDir.resolve(".git/dev-agent-worktrees/task-101");
        Files.createDirectories(worktreeDir);

        signatureExtractor = new ErrorSignatureExtractor();
        subprocessExecutor = Mockito.mock(SubprocessExecutor.class);
        circuitBreaker = new LoopCircuitBreaker(signatureExtractor, subprocessExecutor);

        when(subprocessExecutor.execute(any(), any(), any()))
            .thenReturn(ProcessResult.builder().exitCode(0).stdOut("HEAD is now at 1234567").stdErr("").timedOut(false).truncated(false).build());
    }

    @Test
    @DisplayName("Given initial state - when checking - then circuit breaker is in NORMAL state")
    void givenInitialState_whenChecking_thenNormal() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.NORMAL);
        assertThat(circuitBreaker.getPostPivotFailures()).isZero();
    }

    @Test
    @DisplayName("Given first error - when recording failure - then returns CONTINUE and remains NORMAL")
    void givenFirstError_whenRecording_thenReturnsContinue() {
        LoopAction action = circuitBreaker.recordFailure("error A", worktreeDir);

        assertThat(action).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.NORMAL);
    }

    @Test
    @DisplayName("Given repeating identical error (A -> A) - when recording - then returns INJECT_WARNING and state is WARNING")
    void givenRepeatingError_whenRecording_thenReturnsInjectWarning() {
        circuitBreaker.recordFailure("error A", worktreeDir);
        LoopAction action = circuitBreaker.recordFailure("error A", worktreeDir);

        assertThat(action).isEqualTo(LoopAction.INJECT_WARNING);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.WARNING);
    }

    @Test
    @DisplayName("Given oscillating error (A -> B -> A) - when recording - then triggers git reset in worktree and returns ROLLBACK_AND_PIVOT")
    void givenOscillatingError_whenRecording_thenTriggersRollbackAndPivot() {
        circuitBreaker.recordFailure("error A", worktreeDir);
        circuitBreaker.recordFailure("error B", worktreeDir);
        LoopAction action = circuitBreaker.recordFailure("error A", worktreeDir);

        assertThat(action).isEqualTo(LoopAction.ROLLBACK_AND_PIVOT);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.PIVOT_ACTIVE);

        verify(subprocessExecutor).execute(
            eq(List.of("git", "reset", "--hard")),
            eq(worktreeDir),
            any(Duration.class)
        );
    }

    @Test
    @DisplayName("Given non-worktree workingDir - when oscillating - then throws SecurityException to protect primary workspace")
    void givenNonWorktreeDir_whenOscillating_thenThrowsSecurityException() {
        Path dangerousDir = tempDir.resolve("primary-workspace");
        circuitBreaker.recordFailure("error A", dangerousDir);
        circuitBreaker.recordFailure("error B", dangerousDir);

        assertThatThrownBy(() -> circuitBreaker.recordFailure("error A", dangerousDir))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Refusing to rollback outside dedicated git worktree");
    }

    @Test
    @DisplayName("Given PIVOT_ACTIVE state - when 3 consecutive failures occur - then trips breaker and preserves disk state without rollback")
    void givenPivotActive_whenThreeConsecutiveFailures_thenTripsBreaker() {
        // Trigger oscillation into PIVOT_ACTIVE
        circuitBreaker.recordFailure("error A", worktreeDir);
        circuitBreaker.recordFailure("error B", worktreeDir);
        circuitBreaker.recordFailure("error A", worktreeDir);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.PIVOT_ACTIVE);

        // Reset invocation count on mock
        Mockito.clearInvocations(subprocessExecutor);

        // Failure 1 post-pivot
        LoopAction act1 = circuitBreaker.recordFailure("error C", worktreeDir);
        assertThat(act1).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getPostPivotFailures("task-101")).isEqualTo(1);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.PIVOT_ACTIVE);

        // Failure 2 post-pivot
        LoopAction act2 = circuitBreaker.recordFailure("error D", worktreeDir);
        assertThat(act2).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getPostPivotFailures("task-101")).isEqualTo(2);

        // Failure 3 post-pivot -> EXHAUSTION!
        LoopAction act3 = circuitBreaker.recordFailure("error E", worktreeDir);
        assertThat(act3).isEqualTo(LoopAction.TRIP_AND_HALT);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.TRIPPED);

        // Verify git reset was NOT called during trip (diagnostic state preserved)
        verify(subprocessExecutor, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("Given two distinct tasks - when recording failures - then states remain isolated without cross-task pollution")
    void givenDistinctTasks_whenRecordingFailures_thenStatesRemainIsolated() throws IOException {
        Path worktree1 = tempDir.resolve(".git/dev-agent-worktrees/task-1");
        Path worktree2 = tempDir.resolve(".git/dev-agent-worktrees/task-2");
        Files.createDirectories(worktree1);
        Files.createDirectories(worktree2);

        // Task 1 fails with A -> A (WARNING)
        circuitBreaker.recordFailure("error A", worktree1);
        circuitBreaker.recordFailure("error A", worktree1);
        assertThat(circuitBreaker.getState("task-1")).isEqualTo(CircuitBreakerState.WARNING);

        // Task 2 fails with error A for the first time -> Should be CONTINUE and NORMAL!
        LoopAction task2Action = circuitBreaker.recordFailure("error A", worktree2);
        assertThat(task2Action).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getState("task-2")).isEqualTo(CircuitBreakerState.NORMAL);

        // Resetting task 1 does not affect task 2
        circuitBreaker.reset("task-1");
        assertThat(circuitBreaker.getState("task-1")).isEqualTo(CircuitBreakerState.NORMAL);
    }

    @Test
    @DisplayName("Given failures recorded - when recordSuccess is called - then resets to NORMAL state and clears counters")
    void givenFailures_whenRecordSuccess_thenResetsState() {
        circuitBreaker.recordFailure("error A", worktreeDir);
        circuitBreaker.recordFailure("error A", worktreeDir);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.WARNING);

        circuitBreaker.recordSuccess("task-101");

        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.NORMAL);
        assertThat(circuitBreaker.getPostPivotFailures("task-101")).isZero();

        // Following failure should be treated as a fresh first failure
        LoopAction nextAction = circuitBreaker.recordFailure("error A", worktreeDir);
        assertThat(nextAction).isEqualTo(LoopAction.CONTINUE);

        // Test manual reset all
        circuitBreaker.reset();
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.NORMAL);

        // Test recordFailure without workingDir (defaults to default task)
        LoopAction actionNoDir = circuitBreaker.recordFailure("error X");
        assertThat(actionNoDir).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.NORMAL);
        circuitBreaker.recordSuccess();

        // Test recordFailure with null rawOutput
        LoopAction actionNull = circuitBreaker.recordFailure(null);
        assertThat(actionNull).isEqualTo(LoopAction.CONTINUE);

        // Test three distinct errors (A -> B -> C)
        circuitBreaker.reset();
        circuitBreaker.recordFailure("error 1", worktreeDir);
        circuitBreaker.recordFailure("error 2", worktreeDir);
        LoopAction action3 = circuitBreaker.recordFailure("error 3", worktreeDir);
        assertThat(action3).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.NORMAL);

        // Test oscillation with workingDir == null
        circuitBreaker.reset();
        circuitBreaker.recordFailure("error 1");
        circuitBreaker.recordFailure("error 2");
        LoopAction actionOscNoDir = circuitBreaker.recordFailure("error 1");
        assertThat(actionOscNoDir).isEqualTo(LoopAction.ROLLBACK_AND_PIVOT);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.PIVOT_ACTIVE);

        // Test tripping with workingDir == null
        circuitBreaker.recordFailure("error post 1");
        circuitBreaker.recordFailure("error post 2");
        LoopAction actionTripNoDir = circuitBreaker.recordFailure("error post 3");
        assertThat(actionTripNoDir).isEqualTo(LoopAction.TRIP_AND_HALT);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.TRIPPED);

        // Test exceeding MAX_HISTORY_SIZE
        circuitBreaker.reset();
        for (int i = 0; i < 60; i++) {
            circuitBreaker.recordFailure("unique error " + i);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.NORMAL);

        // Test null and blank task IDs
        assertThat(circuitBreaker.getState(null)).isEqualTo(CircuitBreakerState.NORMAL);
        assertThat(circuitBreaker.getState("")).isEqualTo(CircuitBreakerState.NORMAL);
        assertThat(circuitBreaker.getPostPivotFailures(null)).isZero();
        assertThat(circuitBreaker.getPostPivotFailures("")).isZero();
        circuitBreaker.recordSuccess(null);
        circuitBreaker.recordSuccess("");
        circuitBreaker.reset(null);
        circuitBreaker.recordFailure(null, "error null task", null);
        circuitBreaker.recordFailure("", "error blank task", null);

        // Test nested worktree subdirectory
        Path subDir = worktreeDir.resolve("src/main/java");
        circuitBreaker.recordFailure("error sub", subDir);
        assertThat(circuitBreaker.getState("task-101")).isEqualTo(CircuitBreakerState.NORMAL);
    }
}
