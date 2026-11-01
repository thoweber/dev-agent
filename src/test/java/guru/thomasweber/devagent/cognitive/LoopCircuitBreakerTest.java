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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoopCircuitBreakerTest {

    @TempDir
    Path tempDir;

    private ErrorSignatureExtractor signatureExtractor;
    private SubprocessExecutor subprocessExecutor;
    private LoopCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
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
        LoopAction action = circuitBreaker.recordFailure("error A", tempDir);

        assertThat(action).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.NORMAL);
    }

    @Test
    @DisplayName("Given repeating identical error (A -> A) - when recording - then returns INJECT_WARNING and state is WARNING")
    void givenRepeatingError_whenRecording_thenReturnsInjectWarning() {
        circuitBreaker.recordFailure("error A", tempDir);
        LoopAction action = circuitBreaker.recordFailure("error A", tempDir);

        assertThat(action).isEqualTo(LoopAction.INJECT_WARNING);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.WARNING);
    }

    @Test
    @DisplayName("Given oscillating error (A -> B -> A) - when recording - then triggers git reset and returns ROLLBACK_AND_PIVOT")
    void givenOscillatingError_whenRecording_thenTriggersRollbackAndPivot() {
        circuitBreaker.recordFailure("error A", tempDir);
        circuitBreaker.recordFailure("error B", tempDir);
        LoopAction action = circuitBreaker.recordFailure("error A", tempDir);

        assertThat(action).isEqualTo(LoopAction.ROLLBACK_AND_PIVOT);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.PIVOT_ACTIVE);

        verify(subprocessExecutor).execute(
            eq(List.of("git", "reset", "--hard")),
            eq(tempDir),
            any(Duration.class)
        );
    }

    @Test
    @DisplayName("Given PIVOT_ACTIVE state - when 3 consecutive failures occur - then trips breaker and returns TRIP_AND_HALT")
    void givenPivotActive_whenThreeConsecutiveFailures_thenTripsBreaker() {
        // Trigger oscillation into PIVOT_ACTIVE
        circuitBreaker.recordFailure("error A", tempDir);
        circuitBreaker.recordFailure("error B", tempDir);
        circuitBreaker.recordFailure("error A", tempDir);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.PIVOT_ACTIVE);

        // Failure 1 post-pivot
        LoopAction act1 = circuitBreaker.recordFailure("error C", tempDir);
        assertThat(act1).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getPostPivotFailures()).isEqualTo(1);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.PIVOT_ACTIVE);

        // Failure 2 post-pivot
        LoopAction act2 = circuitBreaker.recordFailure("error D", tempDir);
        assertThat(act2).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getPostPivotFailures()).isEqualTo(2);

        // Failure 3 post-pivot -> EXHAUSTION!
        LoopAction act3 = circuitBreaker.recordFailure("error E", tempDir);
        assertThat(act3).isEqualTo(LoopAction.TRIP_AND_HALT);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.TRIPPED);
    }

    @Test
    @DisplayName("Given failures recorded - when recordSuccess is called - then resets to NORMAL state and clears counters")
    void givenFailures_whenRecordSuccess_thenResetsState() {
        circuitBreaker.recordFailure("error A", tempDir);
        circuitBreaker.recordFailure("error A", tempDir);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.WARNING);

        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.NORMAL);
        assertThat(circuitBreaker.getPostPivotFailures()).isZero();

        // Following failure should be treated as a fresh first failure
        LoopAction nextAction = circuitBreaker.recordFailure("error A", tempDir);
        assertThat(nextAction).isEqualTo(LoopAction.CONTINUE);

        // Test manual reset
        circuitBreaker.reset();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.NORMAL);

        // Test recordFailure without workingDir
        LoopAction actionNoDir = circuitBreaker.recordFailure("error X");
        assertThat(actionNoDir).isEqualTo(LoopAction.CONTINUE);

        // Test recordFailure with null rawOutput
        LoopAction actionNull = circuitBreaker.recordFailure(null);
        assertThat(actionNull).isEqualTo(LoopAction.CONTINUE);

        // Test three distinct errors (A -> B -> C)
        circuitBreaker.reset();
        circuitBreaker.recordFailure("error 1", tempDir);
        circuitBreaker.recordFailure("error 2", tempDir);
        LoopAction action3 = circuitBreaker.recordFailure("error 3", tempDir);
        assertThat(action3).isEqualTo(LoopAction.CONTINUE);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.NORMAL);

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
    }
}
