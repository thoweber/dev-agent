package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitExcludeManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given git repository - when ensureExcluded called multiple times - then adds target-agent and agent entries idempotently")
    void givenGitRepo_whenEnsureExcluded_thenAddsEntriesIdempotently() throws IOException {
        Files.createDirectories(tempDir.resolve(".git/info"));
        Path excludeFile = tempDir.resolve(".git/info/exclude");
        Files.writeString(excludeFile, "# existing comment\n*.iml\n");

        GitExcludeManager manager = new GitExcludeManager();
        manager.ensureExcluded(tempDir, "target-agent");
        manager.ensureExcluded(tempDir, "target-agent/");

        List<String> lines = Files.readAllLines(excludeFile);
        assertThat(lines).contains("# existing comment", "*.iml", "target-agent/", ".cache-agent/", "state.json", "worker.log");
        assertThat(lines.stream().filter(line -> line.equals("target-agent/")).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Given directory without .git - when ensureExcluded called - then skips gracefully")
    void givenNonGitDirectory_whenEnsureExcluded_thenSkipsGracefully() {
        GitExcludeManager manager = new GitExcludeManager();
        manager.ensureExcluded(tempDir, "target-agent");

        assertThat(tempDir.resolve(".git")).doesNotExist();
    }

    @Test
    @DisplayName("Given symlinked .git/info/exclude - when ensureExcluded called - then throws SecurityException")
    void givenSymlinkedExclude_whenEnsureExcluded_thenThrowsSecurityException() throws IOException {
        Files.createDirectories(tempDir.resolve(".git/info"));
        Path externalFile = Files.createTempFile("external", ".txt");
        Path excludeFile = tempDir.resolve(".git/info/exclude");
        Files.createSymbolicLink(excludeFile, externalFile);

        GitExcludeManager manager = new GitExcludeManager();
        assertThatThrownBy(() -> manager.ensureExcluded(tempDir, "target-agent"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Symbolic link");
        Files.deleteIfExists(externalFile);
    }

    @Test
    @DisplayName("Given null repoRoot or blank targetDir - when ensureExcluded called - then handles gracefully")
    void givenNullRepoRootOrBlankTargetDir_whenEnsureExcluded_thenHandlesGracefully() throws IOException {
        GitExcludeManager manager = new GitExcludeManager();
        manager.ensureExcluded(null, "target-agent");

        Files.createDirectories(tempDir.resolve(".git"));
        manager.ensureExcluded(tempDir, "   ");
        manager.ensureExcluded(tempDir, null);

        Path excludeFile = tempDir.resolve(".git/info/exclude");
        assertThat(Files.readAllLines(excludeFile)).contains("target-agent/", ".cache-agent/");
    }

    @Test
    @DisplayName("Given worktree .git pointer file with commondir - when ensureExcluded called - then updates common exclude file")
    void givenWorktreePointerWithCommondir_whenEnsureExcluded_thenUpdatesCommonExclude() throws IOException {
        Path commonGit = Files.createDirectories(tempDir.resolve("main/.git"));
        Path worktreeGitDir = Files.createDirectories(commonGit.resolve("worktrees/TASK-1"));
        Files.writeString(worktreeGitDir.resolve("commondir"), "../..\n");
        Path worktreeRoot = Files.createDirectories(tempDir.resolve("wt"));
        Files.writeString(worktreeRoot.resolve(".git"), "gitdir: " + worktreeGitDir + "\n");

        GitExcludeManager manager = new GitExcludeManager();
        manager.ensureExcluded(worktreeRoot, "target-agent");

        assertThat(Files.readAllLines(commonGit.resolve("info/exclude"))).contains("target-agent/", "state.json");
    }

    @Test
    @DisplayName("Given worktree .git pointer file with relative gitdir and no commondir or invalid content - when ensureExcluded called - then handles both cases")
    void givenRelativeOrInvalidDotGitFile_whenEnsureExcluded_thenHandlesGracefully() throws IOException {
        Path relGitDir = Files.createDirectories(tempDir.resolve("relgit"));
        Path wt1 = Files.createDirectories(tempDir.resolve("wt1"));
        Files.writeString(wt1.resolve(".git"), "gitdir: ../relgit\n");

        GitExcludeManager manager = new GitExcludeManager();
        manager.ensureExcluded(wt1, "target-agent");
        assertThat(Files.readAllLines(relGitDir.resolve("info/exclude"))).contains("target-agent/");

        Path wt2 = Files.createDirectories(tempDir.resolve("wt2"));
        Files.writeString(wt2.resolve(".git"), "not-a-gitdir\n");
        manager.ensureExcluded(wt2, "target-agent");
        assertThat(wt2.resolve("info/exclude")).doesNotExist();
    }

    @Test
    @DisplayName("Given symlinked .git or .git/info - when ensureExcluded called - then throws SecurityException")
    void givenSymlinkedDotGitOrInfo_whenEnsureExcluded_thenThrowsSecurityException() throws IOException {
        Path targetDir = Files.createDirectories(tempDir.resolve("target-dir"));
        Path repoA = Files.createDirectories(tempDir.resolve("repoA"));
        Files.createSymbolicLink(repoA.resolve(".git"), targetDir);

        GitExcludeManager manager = new GitExcludeManager();
        assertThatThrownBy(() -> manager.ensureExcluded(repoA, "target-agent"))
            .isInstanceOf(SecurityException.class);

        Path repoB = Files.createDirectories(tempDir.resolve("repoB"));
        Files.createDirectories(repoB.resolve(".git"));
        Files.createSymbolicLink(repoB.resolve(".git/info"), targetDir);
        assertThatThrownBy(() -> manager.ensureExcluded(repoB, "target-agent"))
            .isInstanceOf(SecurityException.class);

        Path repoC = Files.createDirectories(tempDir.resolve("repoC"));
        Path symGitDir = tempDir.resolve("sym-gitdir");
        Files.createSymbolicLink(symGitDir, targetDir);
        Files.writeString(repoC.resolve(".git"), "gitdir: " + symGitDir + "\n");
        assertThatThrownBy(() -> manager.ensureExcluded(repoC, "target-agent"))
            .isInstanceOf(SecurityException.class);

        Path repoD = Files.createDirectories(tempDir.resolve("repoD"));
        Path realGitDir = Files.createDirectories(tempDir.resolve("real-gitdir"));
        Path commonFile = Files.createTempFile("common", ".txt");
        Files.createSymbolicLink(realGitDir.resolve("commondir"), commonFile);
        Files.writeString(repoD.resolve(".git"), "gitdir: " + realGitDir + "\n");
        assertThatThrownBy(() -> manager.ensureExcluded(repoD, "target-agent"))
            .isInstanceOf(SecurityException.class);
        Files.deleteIfExists(commonFile);

        // Symlinked commonGitDir referenced by commondir + IOException when exclude is a directory
        Path repoE = Files.createDirectories(tempDir.resolve("repoE"));
        Path realGitDirE = Files.createDirectories(tempDir.resolve("real-gitdir-e"));
        Path symCommonDir = tempDir.resolve("sym-commondir-target");
        Files.createSymbolicLink(symCommonDir, targetDir);
        Files.writeString(realGitDirE.resolve("commondir"), "../sym-commondir-target\n");
        Files.writeString(repoE.resolve(".git"), "gitdir: " + realGitDirE + "\n");
        assertThatThrownBy(() -> manager.ensureExcluded(repoE, "target-agent"))
            .isInstanceOf(SecurityException.class);

        Path repoDirAsExclude = Files.createDirectories(tempDir.resolve("repoDirExclude"));
        Files.createDirectories(repoDirAsExclude.resolve(".git/info/exclude"));
        assertThatThrownBy(() -> manager.ensureExcluded(repoDirAsExclude, "target-agent"))
            .isInstanceOf(WorkspaceException.class);
    }
}
