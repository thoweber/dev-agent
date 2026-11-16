package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorktreeExecutionContextTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given unbound WorktreeExecutionContext - when queried - then returns empty")
    void givenUnboundContext_whenQueried_thenReturnsEmpty() {
        assertThat(WorktreeExecutionContext.currentWorktreeRoot()).isEmpty();
    }

    @Test
    @DisplayName("Given bound worktree path - when runInWorktree executed - then binds inside scope and unbinds afterwards")
    void givenBoundWorktree_whenRunInWorktree_thenBindsAndUnbinds() throws Exception {
        Path worktree = tempDir.resolve("wt-1");
        Path nested = tempDir.resolve("wt-2");

        String result = WorktreeExecutionContext.runInWorktree(worktree, () -> {
            assertThat(WorktreeExecutionContext.currentWorktreeRoot()).contains(worktree);

            AtomicReference<Path> inner = new AtomicReference<>();
            WorktreeExecutionContext.runInWorktree(nested, () -> inner.set(WorktreeExecutionContext.currentWorktreeRoot().orElseThrow()));
            assertThat(inner.get()).isEqualTo(nested);
            assertThat(WorktreeExecutionContext.currentWorktreeRoot()).contains(worktree);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(WorktreeExecutionContext.currentWorktreeRoot()).isEmpty();
    }

    @Test
    @DisplayName("Given null worktree path - when runInWorktree called - then throws IllegalArgumentException")
    void givenNullWorktree_whenRunInWorktree_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> WorktreeExecutionContext.runInWorktree(null, () -> "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorktreeExecutionContext.runInWorktree(null, () -> {}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
