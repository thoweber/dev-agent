package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.model.TaskId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorktreeLayoutTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given repository root and TaskId - when WorktreeLayout queried - then resolves canonical worktree, socket, and log paths")
    void givenRepoRootAndTaskId_whenQueried_thenResolvesPaths() throws IOException {
        WorktreeLayout layout = WorktreeLayout.forRoot(tempDir);
        TaskId taskId = TaskId.of("TASK-LAYOUT-1");

        assertThat(layout.repoRoot()).isEqualTo(tempDir.toRealPath());
        assertThat(layout.worktreesRoot()).isEqualTo(tempDir.toRealPath().resolve(".git/dev-agent-worktrees"));
        assertThat(layout.worktreeDir(taskId)).isEqualTo(layout.worktreesRoot().resolve("TASK-LAYOUT-1"));
        assertThat(layout.stateFile(taskId)).isEqualTo(layout.worktreeDir(taskId).resolve("state.json"));
        assertThat(layout.workerLogFile(taskId)).isEqualTo(layout.worktreeDir(taskId).resolve("worker.log"));
    }

    @Test
    @DisplayName("Given worktree subpath under symlinked mount ancestor - when detectWorktreeRoot called - then still resolves worktree root")
    void givenSymlinkedAncestorAboveRepo_whenDetectWorktreeRoot_thenResolves() throws IOException {
        Path realMount = Files.createDirectories(tempDir.resolve("real-mount"));
        Path symMount = Files.createSymbolicLink(tempDir.resolve("sym-mount"), realMount);
        Path realWorktree = Files.createDirectories(realMount.resolve("repo/.git/dev-agent-worktrees/TASK-MAC/src"));
        Path viaSym = symMount.resolve("repo/.git/dev-agent-worktrees/TASK-MAC/src");

        assertThat(WorktreeLayout.detectWorktreeRoot(viaSym))
            .isPresent()
            .get()
            .extracting(Path::getFileName)
            .extracting(Path::toString)
            .isEqualTo("TASK-MAC");
        assertThat(WorktreeLayout.detectWorktreeRoot(realWorktree)).isPresent();
    }

    @Test
    @DisplayName("Given worktree subpath or symlinked .git ancestor - when detected or validated - then resolves root or throws SecurityException")
    void givenSubpathOrSymlink_whenDetectedOrValidated_thenBehavesSafely() throws IOException {
        Path nested = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-900/src/main"));
        assertThat(WorktreeLayout.detectWorktreeRoot(nested))
            .contains(tempDir.resolve(".git/dev-agent-worktrees/TASK-900").toAbsolutePath().normalize());
        assertThat(WorktreeLayout.detectWorktreeRoot(tempDir.resolve("regular/dir"))).isEmpty();
        assertThat(WorktreeLayout.detectWorktreeRoot(null)).isEmpty();
        WorktreeLayout.validateNoSymlinks(tempDir, null);

        Path external = Files.createDirectories(tempDir.resolve("external"));
        Path repoWithSymGit = Files.createDirectories(tempDir.resolve("repo-sym"));
        Files.createSymbolicLink(repoWithSymGit.resolve(".git"), external);
        WorktreeLayout layout = WorktreeLayout.forRoot(repoWithSymGit);

        assertThatThrownBy(() -> layout.stateFile(TaskId.of("TASK-SYM")))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> WorktreeLayout.forRoot(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
