package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeStateMotherTest {

    @Test
    @DisplayName("Given WorktreeStateMother factories - when invoked - then produce valid canonical fixtures")
    void givenMotherFactories_whenInvoked_thenProduceCanonicalFixtures() {
        WorktreeState active = WorktreeStateMother.active("TASK-1", 1234L);
        WorktreeState queued = WorktreeStateMother.queued("TASK-2");
        WorktreeState crashed = WorktreeStateMother.crashed("TASK-3");
        WorktreeState completed = WorktreeStateMother.completed("TASK-4");

        assertThat(active.status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(active.pid()).isEqualTo(1234L);
        assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.pid()).isNull();
        assertThat(crashed.status()).isEqualTo(TaskStatus.GREEN_PATCH);
        assertThat(crashed.pid()).isEqualTo(WorktreeStateMother.DEAD_PID);
        assertThat(completed.status()).isEqualTo(TaskStatus.COMPLETED);
    }
}
