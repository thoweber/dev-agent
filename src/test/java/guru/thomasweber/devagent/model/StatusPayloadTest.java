package guru.thomasweber.devagent.model;

import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatusPayloadTest {

    @Test
    @DisplayName("Given null activeTasks list - when StatusPayload constructed - then normalizes to immutable empty list")
    void givenNullActiveTasks_whenConstructed_thenReturnsEmptyList() {
        StatusPayload payload = new StatusPayload(null, "No active background tasks detected.");

        assertThat(payload.activeTasks()).isEmpty();
        assertThat(payload.message()).isEqualTo("No active background tasks detected.");
    }

    @Test
    @DisplayName("Given active tasks - when StatusPayload constructed - then preserves immutable copy")
    void givenActiveTasks_whenConstructed_thenPreservesTasks() {
        WorktreeState active = WorktreeStateMother.active("TASK-403", 999L);
        StatusPayload payload = new StatusPayload(List.of(active), "Found 1 active task.");

        assertThat(payload.activeTasks()).containsExactly(active);
        assertThat(payload.message()).isEqualTo("Found 1 active task.");
    }
}
