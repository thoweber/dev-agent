package guru.thomasweber.devagent.model;

import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerProcessTest {

    @Test
    @DisplayName("Given invalid or non-positive PIDs - when constructed or resolved via from - then rejects or returns empty")
    void givenInvalidPids_whenConstructed_thenRejectsOrReturnsEmpty() {
        assertThatThrownBy(() -> WorkerProcess.of(0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerProcess.of(-5L, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(WorkerProcess.from(null, null)).isEmpty();
        assertThat(WorkerProcess.from(0L, Instant.now())).isEmpty();
        assertThat(WorkerProcess.from(-1L, null)).isEmpty();
        assertThat(WorkerProcess.fromState(null)).isEmpty();
    }

    @Test
    @DisplayName("Given current or live process - when matched against ProcessHandle - then detects liveness and recycled PID drift")
    void givenLiveProcess_whenMatched_thenDetectsLivenessAndRecycledPidDrift() {
        WorkerProcess current = WorkerProcess.current();
        assertThat(current.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(current.isAlive()).isTrue();
        assertThat(current.matches(ProcessHandle.current())).isTrue();
        assertThat(current.matches(null)).isFalse();

        WorkerProcess wrongPid = WorkerProcess.of(current.pid() + 999_999L, current.startedAt());
        assertThat(wrongPid.matches(ProcessHandle.current())).isFalse();

        WorkerProcess driftedStart = WorkerProcess.of(current.pid(), Instant.now().minusSeconds(3600));
        assertThat(driftedStart.isAlive()).isFalse();

        WorkerProcess unknownStart = WorkerProcess.of(current.pid(), null);
        assertThat(unknownStart.isAlive()).isTrue();

        WorktreeState state = WorktreeStateMother.active("TASK-WP-1", current.pid()).toBuilder()
            .pidStartedAt(current.startedAt())
            .build();
        Optional<WorkerProcess> fromState = WorkerProcess.fromState(state);
        assertThat(fromState).isPresent().contains(current);
    }

    @Test
    @DisplayName("Given live subprocess - when terminateGracefully called - then destroys child cleanly and skips current JVM")
    void givenSubprocess_whenTerminated_thenStopsChildAndProtectsCurrentJvm() throws Exception {
        WorkerProcess.current().terminateGracefully(Duration.ofMillis(50));
        assertThat(WorkerProcess.current().isAlive()).isTrue();

        Process sleeper = new ProcessBuilder("sleep", "30").start();
        try {
            WorkerProcess child = WorkerProcess.of(sleeper.pid());
            assertThat(child.isAlive()).isTrue();

            child.terminateGracefully(null);
            sleeper.waitFor();
            assertThat(child.isAlive()).isFalse();
        } finally {
            sleeper.destroyForcibly();
        }
    }
}
