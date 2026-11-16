package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import guru.thomasweber.devagent.testutil.ProcessResultMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.nonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitWorktreeServiceTest {

    @TempDir
    Path tempDir;

    private final DevAgentConfigLoader configLoader = new DevAgentConfigLoader();

    @Test
    @DisplayName("Given initialized git repository with uncommitted changes - when allocateWorktree and removeWorktree called - then hydrates and cleans up while preserving branch")
    void givenGitRepoWithDirtyFiles_whenAllocateAndRemove_thenHydratesAndPreservesBranch() throws IOException {
        SubprocessExecutor executor = new SubprocessExecutor();
        initGitRepo(executor, tempDir);
        Files.writeString(tempDir.resolve("README.md"), "# initial\n");
        runGit(executor, tempDir, "add", "README.md");
        runGit(executor, tempDir, "commit", "-m", "initial commit");

        // Uncommitted modification in primary workspace
        Files.writeString(tempDir.resolve("README.md"), "# initial\n+uncommitted work\n");

        GitWorktreeService service = new GitWorktreeService(executor, new GitExcludeManager());
        assertThat(service.isGitRepository(tempDir)).isTrue();

        DevAgentConfig.IsolationConfig isolation = configLoader.loadFromYaml("").isolation();
        WorktreeAllocation allocation = service.allocateWorktree(tempDir, "TASK-410", "Add Metrics Dashboard!", isolation, "target-agent");

        assertThat(allocation.taskId()).isEqualTo("TASK-410");
        assertThat(allocation.branch()).isEqualTo("feature/TASK-410-add-metrics-dashboard");
        assertThat(allocation.worktreePath()).isDirectory();
        assertThat(allocation.hydrated()).isTrue();
        assertThat(allocation.checkpointStash()).isNotBlank();
        assertThat(Files.readString(allocation.worktreePath().resolve("README.md"))).contains("+uncommitted work");
        assertThat(service.listWorktreeTaskIds(tempDir)).containsExactly("TASK-410");

        // Commit inside worktree and remove worktree preserving branch
        Files.writeString(allocation.worktreePath().resolve("Feature.java"), "class Feature {}\n");
        assertThat(service.commitChanges(allocation.worktreePath(), "feat: add feature")).isTrue();
        assertThat(service.commitChanges(allocation.worktreePath(), "noop")).isFalse();

        service.removeWorktree(tempDir, "TASK-410", false, allocation.branch());
        assertThat(allocation.worktreePath()).doesNotExist();

        ProcessResult branchCheck = executor.execute(List.of("git", "branch", "--list", allocation.branch()), tempDir, Duration.ofSeconds(5));
        assertThat(branchCheck.stdOut()).contains(allocation.branch());
    }

    @Test
    @DisplayName("Given worktree with changes - when resetToBase and removeWorktree with deleteBranch called - then resets and deletes branch")
    void givenWorktreeWithChanges_whenResetAndRemoveWithDeleteBranch_thenDeletesBranch() throws IOException {
        SubprocessExecutor executor = new SubprocessExecutor();
        initGitRepo(executor, tempDir);
        Files.writeString(tempDir.resolve("Base.txt"), "base\n");
        runGit(executor, tempDir, "add", "Base.txt");
        runGit(executor, tempDir, "commit", "-m", "base");

        GitWorktreeService service = new GitWorktreeService(executor, new GitExcludeManager());
        DevAgentConfig.IsolationConfig isolation = configLoader.loadFromYaml("").isolation();
        WorktreeAllocation allocation = service.allocateWorktree(tempDir, "TASK-411", "Rollback Test", isolation, "target-agent");

        assertThat(allocation.hydrated()).isFalse();
        Files.writeString(allocation.worktreePath().resolve("Base.txt"), "mutated\n");
        Files.writeString(allocation.worktreePath().resolve("state.json"), "{}\n");

        service.resetToBase(allocation.worktreePath(), allocation.baseCommit());
        assertThat(Files.readString(allocation.worktreePath().resolve("Base.txt"))).isEqualTo("base\n");
        assertThat(allocation.worktreePath().resolve("state.json")).isRegularFile();

        service.removeWorktree(tempDir, "TASK-411", true, allocation.branch());
        ProcessResult branchCheck = executor.execute(List.of("git", "branch", "--list", allocation.branch()), tempDir, Duration.ofSeconds(5));
        assertThat(branchCheck.stdOut().trim()).isEmpty();
    }

    @Test
    @DisplayName("Given invalid or traversal taskId - when allocateWorktree or removeWorktree called - then throws SecurityException")
    void givenInvalidTaskId_whenCalled_thenThrowsSecurityException() {
        GitWorktreeService service = new GitWorktreeService();
        DevAgentConfig.IsolationConfig isolation = configLoader.loadFromYaml("").isolation();

        assertThatThrownBy(() -> service.allocateWorktree(tempDir, "../escape", "slug", isolation, "target-agent"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.removeWorktree(tempDir, "bad/id", false, "b"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.allocateWorktree(tempDir, "   ", "slug", isolation, "target-agent"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Given non-git directory or failing git rev-parse - when allocateWorktree called - then throws WorkspaceException")
    void givenFailingGitCommand_whenAllocateWorktree_thenThrowsWorkspaceException() {
        SubprocessExecutor mocked = mock(SubprocessExecutor.class);
        when(mocked.execute(eq(List.of("git", "rev-parse", "HEAD")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(128, "", "fatal: not a git repository"));

        GitWorktreeService service = new GitWorktreeService(mocked, new GitExcludeManager());
        DevAgentConfig.IsolationConfig isolation = configLoader.loadFromYaml("").isolation();

        assertThatThrownBy(() -> service.allocateWorktree(tempDir, "TASK-412", "story", isolation, "target-agent"))
            .isInstanceOf(WorkspaceException.class)
            .hasMessageContaining("Failed to resolve HEAD");
    }

    @Test
    @DisplayName("Given non-git or null directories - when isGitRepository or listWorktreeTaskIds called - then handles gracefully")
    void givenNonGitOrNullDirs_whenChecked_thenHandlesGracefully() {
        GitWorktreeService service = new GitWorktreeService();

        assertThat(service.isGitRepository(null)).isFalse();
        assertThat(service.isGitRepository(tempDir.resolve("missing"))).isFalse();
        assertThat(service.isGitRepository(tempDir)).isFalse();
        assertThat(service.listWorktreeTaskIds(tempDir)).isEmpty();
        assertThatThrownBy(() -> service.allocateWorktree(null, "TASK-1", "slug", null, "target-agent"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given existing branch and long/blank slugs - when allocateWorktree called - then sanitizes slug and re-attaches existing branch")
    void givenExistingBranchAndEdgeSlugs_whenAllocateWorktree_thenReattachesAndSanitizes() throws IOException {
        SubprocessExecutor executor = new SubprocessExecutor();
        initGitRepo(executor, tempDir);
        Files.writeString(tempDir.resolve("App.java"), "class App {}\n");
        runGit(executor, tempDir, "add", "App.java");
        runGit(executor, tempDir, "commit", "-m", "init");

        GitWorktreeService service = new GitWorktreeService(executor, new GitExcludeManager());
        String longSlug = "This is a very long user story description that definitely exceeds forty eight characters in length!";
        WorktreeAllocation first = service.allocateWorktree(tempDir, "TASK-413", longSlug, null, "target-agent");
        assertThat(first.branch()).startsWith("feature/TASK-413-");
        assertThat(first.branch().length()).isLessThanOrEqualTo("feature/TASK-413-".length() + 48);

        // Remove worktree keeping branch, then re-attach to existing branch
        service.removeWorktree(tempDir, "TASK-413", false, first.branch());
        DevAgentConfig.IsolationConfig noStash = configLoader.loadFromYaml("""
            agent:
              isolation:
                auto-stash-uncommitted: false
            """).isolation();
        WorktreeAllocation reattached = service.allocateWorktree(tempDir, "TASK-413", longSlug, noStash, "target-agent");
        assertThat(reattached.branch()).isEqualTo(first.branch());
        assertThat(reattached.hydrated()).isFalse();

        // Blank template mocked + punctuation-only and null slugs fall back to defaults
        DevAgentConfig.IsolationConfig blankTemplate = mock(DevAgentConfig.IsolationConfig.class);
        when(blankTemplate.branchTemplate()).thenReturn("   ");
        when(blankTemplate.autoStashUncommitted()).thenReturn(false);
        WorktreeAllocation fallbackSlug = service.allocateWorktree(tempDir, "TASK-414", "!!!", blankTemplate, "target-agent");
        assertThat(fallbackSlug.branch()).isEqualTo("feature/TASK-414-task");
        WorktreeAllocation nullSlug = service.allocateWorktree(tempDir, "TASK-415", null, noStash, "target-agent");
        assertThat(nullSlug.branch()).isEqualTo("feature/TASK-415-task");
    }

    @Test
    @DisplayName("Given symlinked worktrees directory - when allocateWorktree or removeWorktree called - then throws SecurityException")
    void givenSymlinkedWorktreesDir_whenCalled_thenThrowsSecurityException() throws IOException {
        Path externalDir = Files.createDirectories(tempDir.resolve("external"));
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Files.createSymbolicLink(repo.resolve(".git/dev-agent-worktrees"), externalDir);

        GitWorktreeService service = new GitWorktreeService();
        assertThatThrownBy(() -> service.allocateWorktree(repo, "TASK-416", "slug", null, "target-agent"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.removeWorktree(repo, "TASK-416", false, null))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Given failing git subprocesses across lifecycle methods - when invoked - then throws WorkspaceException")
    void givenFailingGitSubprocesses_whenInvoked_thenThrowsWorkspaceException() {
        SubprocessExecutor mocked = mock(SubprocessExecutor.class);
        GitWorktreeService service = new GitWorktreeService(mocked, new GitExcludeManager());

        when(mocked.execute(eq(List.of("git", "status", "--porcelain")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", "status err"));
        assertThatThrownBy(() -> service.commitChanges(tempDir, "msg"))
            .isInstanceOf(WorkspaceException.class);

        when(mocked.execute(eq(List.of("git", "status", "--porcelain")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(" M File.java\n"));
        when(mocked.execute(eq(List.of("git", "add", "-A")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", "add err"));
        assertThatThrownBy(() -> service.commitChanges(tempDir, "msg"))
            .isInstanceOf(WorkspaceException.class);

        when(mocked.execute(eq(List.of("git", "add", "-A")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""));
        when(mocked.execute(eq(List.of("git", "commit", "-m", "msg")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", "commit err"));
        assertThatThrownBy(() -> service.commitChanges(tempDir, "msg"))
            .isInstanceOf(WorkspaceException.class);

        when(mocked.execute(eq(List.of("git", "reset", "--hard", "HEAD")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", "reset err"));
        assertThatThrownBy(() -> service.resetToBase(tempDir, "HEAD"))
            .isInstanceOf(WorkspaceException.class);

        when(mocked.execute(eq(List.of("git", "reset", "--hard", "HEAD")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""));
        when(mocked.execute(any(), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""))
            .thenReturn(ProcessResultMother.failure(1, "", "clean err"));
        // Stub clean specifically
        when(mocked.execute(
            eq(List.of("git", "clean", "-fd", "-e", "state.json", "-e", "worker.log", "-e", "target-agent", "-e", ".cache-agent")),
            eq(tempDir),
            any(Duration.class)
        )).thenReturn(ProcessResultMother.failure(1, "", "clean err"));
        assertThatThrownBy(() -> service.resetToBase(tempDir, "HEAD"))
            .isInstanceOf(WorkspaceException.class);

        when(mocked.execute(eq(List.of("git", "worktree", "prune")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""));
        when(mocked.execute(eq(List.of("git", "branch", "-D", "feature/x")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", "branch err"));
        assertThatThrownBy(() -> service.removeWorktree(tempDir, "TASK-99", true, "feature/x"))
            .isInstanceOf(WorkspaceException.class);
    }

    @Test
    @DisplayName("Given custom target-dir - when resetToBase with targetDir called - then excludes custom target-dir during git clean")
    void givenCustomTargetDir_whenResetToBase_thenExcludesCustomTargetDir() {
        SubprocessExecutor mocked = mock(SubprocessExecutor.class);
        when(mocked.execute(eq(List.of("git", "reset", "--hard", "HEAD")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""));
        when(mocked.execute(
            eq(List.of("git", "clean", "-fd", "-e", "state.json", "-e", "worker.log", "-e", "target-custom", "-e", ".cache-agent")),
            eq(tempDir),
            any(Duration.class)
        )).thenReturn(ProcessResultMother.success(""));

        GitWorktreeService service = new GitWorktreeService(mocked, new GitExcludeManager());
        service.resetToBase(tempDir, "HEAD", "target-custom");

        verify(mocked).execute(
            eq(List.of("git", "clean", "-fd", "-e", "state.json", "-e", "worker.log", "-e", "target-custom", "-e", ".cache-agent")),
            eq(tempDir),
            any(Duration.class)
        );
    }

    @Test
    @DisplayName("Given flag-like baseCommit or null commit message - when git operations called - then throws SecurityException or IllegalArgumentException")
    void givenUnsafeBaseCommitOrNullMessage_whenCalled_thenRejectsInputs() throws IOException {
        SubprocessExecutor mocked = mock(SubprocessExecutor.class);
        GitWorktreeService service = new GitWorktreeService(mocked, new GitExcludeManager());

        assertThatThrownBy(() -> service.resetToBase(tempDir, "-bad-ref"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.resetToBase(tempDir, "   "))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.commitChanges(tempDir, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.commitChanges(tempDir, "   "))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.isGitRepository(null)).isFalse();
        assertThat(service.isGitRepository(tempDir.resolve("missing"))).isFalse();
        assertThat(service.listWorktreeTaskIds(tempDir.resolve("no-worktrees"))).isEmpty();
        service.removeWorktree(tempDir, "TASK-NO-BRANCH", true, "   ");
        service.removeWorktree(tempDir, "TASK-NULL-BRANCH", true, null);

        // Failing stash apply in hydration + non-stash isolation path
        when(mocked.execute(eq(List.of("git", "rev-parse", "HEAD")), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.success("deadbeef"));
        when(mocked.execute(eq(List.of("git", "worktree", "prune")), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""));
        when(mocked.execute(eq(List.of("git", "show-ref", "--verify", "--quiet", "refs/heads/feature/TASK-HYD-1-slug")), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", ""));
        when(mocked.execute(argThat(cmd -> nonNull(cmd) && cmd.size() >= 3 && "add".equals(cmd.get(2))), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""));
        when(mocked.execute(eq(List.of("git", "status", "--porcelain")), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(" M Dirty.java\n"));
        when(mocked.execute(eq(List.of("git", "stash", "create")), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.success("stashsha123\n"));
        when(mocked.execute(eq(List.of("git", "stash", "apply", "stashsha123")), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", "conflict"));

        assertThatThrownBy(() -> service.allocateWorktree(tempDir, "TASK-HYD-1", "slug", null, "target-agent"))
            .isInstanceOf(WorkspaceException.class);

        // autoStashUncommitted=false skips stash/hydration; failing status/false stdout in isGitRepository; clean commitChanges -> false
        DevAgentConfig.IsolationConfig noStashIso = mock(DevAgentConfig.IsolationConfig.class);
        when(noStashIso.branchTemplate()).thenReturn("feature/{task-id}-{story-slug}");
        when(noStashIso.autoStashUncommitted()).thenReturn(false);
        when(mocked.execute(eq(List.of("git", "show-ref", "--verify", "--quiet", "refs/heads/feature/TASK-NOSTASH-slug")), any(Path.class), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", ""));
        WorktreeAllocation noStashAlloc = service.allocateWorktree(tempDir, "TASK-NOSTASH", "slug", noStashIso, "target-agent");
        assertThat(noStashAlloc.hydrated()).isFalse();

        when(mocked.execute(eq(List.of("git", "status", "--porcelain")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.success(""));
        assertThat(service.commitChanges(tempDir, "no changes")).isFalse();

        when(mocked.execute(eq(List.of("git", "rev-parse", "--is-inside-work-tree")), eq(tempDir), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(128, "", "not git"))
            .thenReturn(ProcessResultMother.success("false"));
        assertThat(service.isGitRepository(tempDir)).isFalse();
        assertThat(service.isGitRepository(tempDir)).isFalse();

        Path wtDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-SUB"));
        Files.writeString(tempDir.resolve(".git/dev-agent-worktrees/not-a-dir.txt"), "x");
        assertThat(service.listWorktreeTaskIds(tempDir)).containsExactly("TASK-SUB");
        assertThat(wtDir).isDirectory();
    }

    private static void initGitRepo(SubprocessExecutor executor, Path dir) {
        runGit(executor, dir, "init");
        runGit(executor, dir, "config", "user.email", "test@dev-agent.local");
        runGit(executor, dir, "config", "user.name", "Dev Agent Test");
    }

    private static void runGit(SubprocessExecutor executor, Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessResult result = executor.execute(cmd, dir, Duration.ofSeconds(10));
        assertThat(result.isSuccess()).as("git %s failed: %s", String.join(" ", args), result.stdErr()).isTrue();
    }
}
