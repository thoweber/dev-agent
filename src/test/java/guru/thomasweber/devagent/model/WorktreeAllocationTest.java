package guru.thomasweber.devagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeAllocationTest {

    @Test
    @DisplayName("Given WorktreeAllocation builder - when built and copied via toBuilder - then exposes all allocation metadata")
    void givenAllocationBuilder_whenBuilt_thenExposesFields() {
        Path worktreePath = Path.of(".git/dev-agent-worktrees/TASK-402");
        WorktreeAllocation allocation = WorktreeAllocation.builder()
            .taskId("TASK-402")
            .branch("feature/TASK-402-cache")
            .worktreePath(worktreePath)
            .baseCommit("abc123def456")
            .checkpointStash("stash123")
            .hydrated(true)
            .build();

        assertThat(allocation.taskId()).isEqualTo("TASK-402");
        assertThat(allocation.branch()).isEqualTo("feature/TASK-402-cache");
        assertThat(allocation.worktreePath()).isEqualTo(worktreePath);
        assertThat(allocation.baseCommit()).isEqualTo("abc123def456");
        assertThat(allocation.checkpointStash()).isEqualTo("stash123");
        assertThat(allocation.hydrated()).isTrue();

        WorktreeAllocation nonHydrated = allocation.toBuilder()
            .hydrated(false)
            .checkpointStash(null)
            .build();
        assertThat(nonHydrated.hydrated()).isFalse();
        assertThat(nonHydrated.checkpointStash()).isNull();
    }
}
