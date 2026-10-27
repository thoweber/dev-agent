package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoPathTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid path inside root - when RepoPath.of called - returns valid RepoPath")
    void testValidRepoPathCreation() throws IOException {
        Path validFile = tempDir.resolve("src/main/App.java");
        Files.createDirectories(validFile.getParent());
        Files.writeString(validFile, "public class App {}");

        RepoPath repoPath = RepoPath.of(validFile, tempDir);

        assertThat(repoPath.toPath()).isEqualTo(validFile.toAbsolutePath().normalize());
        assertThat(repoPath.root()).isEqualTo(tempDir.toRealPath());
        assertThat(repoPath.relativize()).isEqualTo(Path.of("src/main/App.java"));
    }

    @Test
    @DisplayName("Given traversal syntax '..' in path - when RepoPath.of called - throws SecurityException")
    void testRejectsDirectoryTraversal() {
        Path malicious = tempDir.resolve("../evil.sh");

        assertThatThrownBy(() -> RepoPath.of(malicious, tempDir))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Traversal detected");
    }

    @Test
    @DisplayName("Given candidate path is a symbolic link - when RepoPath.of called - throws SecurityException")
    void testRejectsSymbolicLink() throws IOException {
        Path outsideFile = tempDir.resolve("target.txt");
        Files.writeString(outsideFile, "secret");

        Path symlink = tempDir.resolve("link-to-secret.txt");
        Files.createSymbolicLink(symlink, outsideFile);

        assertThatThrownBy(() -> RepoPath.of(symlink, tempDir))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Symbolic links are forbidden");
    }

    @Test
    @DisplayName("Given candidate resolves into protected .git directory - when RepoPath.of called - throws SecurityException")
    void testRejectsGitDirectory() throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Path gitConfig = gitDir.resolve("config");

        assertThatThrownBy(() -> RepoPath.of(gitConfig, tempDir))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Protected location");
    }

    @Test
    @DisplayName("Given valid RepoPath - when resolve called with valid child - returns child RepoPath")
    void testResolveValidChild() throws IOException {
        Path subDir = tempDir.resolve(".agents/runs");
        Files.createDirectories(subDir);

        RepoPath runsDir = RepoPath.of(subDir, tempDir);
        RepoPath taskJournal = runsDir.resolve("TASK-001.md");

        assertThat(taskJournal.relativize()).isEqualTo(Path.of(".agents/runs/TASK-001.md"));
        assertThat(taskJournal.toPath()).isEqualTo(tempDir.toRealPath().resolve(".agents/runs/TASK-001.md"));
    }

    @Test
    @DisplayName("Given valid RepoPath - when resolve called with traversal child - throws SecurityException")
    void testResolveTraversalChild() {
        RepoPath root = RepoPath.of(tempDir, tempDir);

        assertThatThrownBy(() -> root.resolve("../../etc/passwd"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Traversal detected");
    }

    @Test
    @DisplayName("Given direct constructor call with path outside root - throws SecurityException")
    void testDirectConstructorEnforcesValidation() {
        assertThatThrownBy(() -> new RepoPath(Path.of("/outside/file.txt"), tempDir))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Given valid filename containing double dots - does not trigger false positive traversal")
    void testValidFilenameWithDotsAllowed() throws IOException {
        Path validFile = tempDir.resolve("backup..v2.json");
        Files.writeString(validFile, "{}");

        RepoPath repoPath = RepoPath.of(validFile, tempDir);
        assertThat(repoPath.toPath().getFileName().toString()).isEqualTo("backup..v2.json");
    }

    @Test
    @DisplayName("Given symlink ancestor directory - throws SecurityException")
    void testIntermediateSymlinkRejected() throws IOException {
        Path realSub = Files.createDirectories(tempDir.resolve("real_sub"));
        Path symlinkSub = tempDir.resolve("sym_sub");
        Files.createSymbolicLink(symlinkSub, realSub);

        assertThatThrownBy(() -> RepoPath.of(symlinkSub.resolve("child.txt"), tempDir))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Symbolic links are forbidden");
    }
}
