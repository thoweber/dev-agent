package org.quarkus.agent.testutil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorktreeTestFixtureTest {

    @TempDir
    Path tempRepo;

    @Test
    @DisplayName("Given repo root and taskId - when resolveWorktree called - returns path under .git/dev-agent-worktrees")
    void testResolveWorktree() {
        Path worktree = WorktreeTestFixture.resolveWorktree(tempRepo, "TASK-123");

        assertThat(worktree).isEqualTo(tempRepo.resolve(".git").resolve("dev-agent-worktrees").resolve("TASK-123"));
    }

    @Test
    @DisplayName("Given repo root and taskId - when createWorktree called - creates physical directory")
    void testCreateWorktree() throws IOException {
        Path worktree = WorktreeTestFixture.createWorktree(tempRepo, "TASK-123");

        assertThat(Files.isDirectory(worktree)).isTrue();
    }

    @Test
    @DisplayName("Given existing worktree - when deleteWorktree called - deletes directory cleanly")
    void testDeleteWorktree() throws IOException {
        Path worktree = WorktreeTestFixture.createWorktree(tempRepo, "TASK-123");
        assertThat(Files.exists(worktree)).isTrue();

        WorktreeTestFixture.deleteWorktree(tempRepo, "TASK-123");
        assertThat(Files.exists(worktree)).isFalse();

        // Calling delete on non-existent worktree should be a safe no-op
        WorktreeTestFixture.deleteWorktree(tempRepo, "TASK-123");
    }

    @Test
    @DisplayName("Given multiple worktrees - when cleanAllWorktrees called - removes all worktree directories")
    void testCleanAllWorktrees() throws IOException {
        Path wt1 = WorktreeTestFixture.createWorktree(tempRepo, "TASK-001");
        Path wt2 = WorktreeTestFixture.createWorktree(tempRepo, "TASK-002");
        assertThat(Files.exists(wt1)).isTrue();
        assertThat(Files.exists(wt2)).isTrue();

        WorktreeTestFixture.cleanAllWorktrees(tempRepo);

        assertThat(Files.exists(wt1)).isFalse();
        assertThat(Files.exists(wt2)).isFalse();
    }

    @Test
    @DisplayName("Given non-existent worktree directory - when cleanAllWorktrees called - handles safely without exception")
    void testCleanAllWorktreesWhenDirectoryDoesNotExist() {
        Path nonExistent = tempRepo.resolve("no-such-repo");
        assertThatCode(() -> WorktreeTestFixture.cleanAllWorktrees(nonExistent))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Given existing worktree directory - when createWorktree called again - throws exception and prevents overwrite")
    void testCreateWorktreeThrowsWhenDirectoryExists() throws IOException {
        WorktreeTestFixture.createWorktree(tempRepo, "TASK-EXISTING");

        assertThatThrownBy(() -> WorktreeTestFixture.createWorktree(tempRepo, "TASK-EXISTING"))
            .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Given worktrees in distinct repositories - when cleanAllWorktrees called for one repo - preserves other repo worktrees")
    void testCleanAllWorktreesPreservesOtherRepositories(@TempDir Path repoB) throws IOException {
        Path wtA = WorktreeTestFixture.createWorktree(tempRepo, "TASK-A");
        Path wtB = WorktreeTestFixture.createWorktree(repoB, "TASK-B");

        assertThat(Files.exists(wtA)).isTrue();
        assertThat(Files.exists(wtB)).isTrue();

        WorktreeTestFixture.cleanAllWorktrees(tempRepo);

        assertThat(Files.exists(wtA)).isFalse();
        assertThat(Files.exists(wtB)).isTrue();

        WorktreeTestFixture.cleanAllWorktrees(repoB);
        assertThat(Files.exists(wtB)).isFalse();
    }
}
