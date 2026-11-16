package guru.thomasweber.devagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeStateTest {

    @Test
    @DisplayName("Given WorktreeState builder with null cost - when built - then defaults cost to zero")
    void givenNullCost_whenBuilt_thenDefaultsCostToZero() {
        Instant now = Instant.parse("2026-11-16T20:00:00Z");
        WorktreeState state = WorktreeState.builder()
            .taskId("TASK-401")
            .projectId("proj-1")
            .status(TaskStatus.PLANNING)
            .phase("PLANNING")
            .tokens(1500L)
            .cost(null)
            .pid(4242L)
            .branch("feature/TASK-401-metrics")
            .worktreePath(".git/dev-agent-worktrees/TASK-401")
            .startedAt(now)
            .updatedAt(now)
            .lastMessage("Initialized worktree")
            .build();

        assertThat(state.taskId()).isEqualTo("TASK-401");
        assertThat(state.typedTaskId()).isEqualTo(TaskId.of("TASK-401"));
        assertThat(WorktreeState.builder().taskId(null).build().taskId()).isNull();
        assertThat(state.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(state.status()).isEqualTo(TaskStatus.PLANNING);
        assertThat(state.tokens()).isEqualTo(1500L);
        assertThat(state.pid()).isEqualTo(4242L);

        WorktreeState updated = state.toBuilder()
            .status(TaskStatus.COMPLETED)
            .cost(new BigDecimal("0.42"))
            .build();
        assertThat(updated.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(updated.cost()).isEqualByComparingTo("0.42");
        assertThat(updated.taskId()).isEqualTo("TASK-401");
    }
}
