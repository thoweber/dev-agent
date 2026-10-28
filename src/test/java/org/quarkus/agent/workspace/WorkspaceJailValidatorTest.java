package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceJailValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid relative path - when resolved within boundary - then returns normalized target")
    void testValidPathResolvesWithinBoundary() throws IOException {
        Path validFile = Files.createFile(tempDir.resolve("sample.txt"));
        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);

        Path resolved = validator.validateAndResolve("sample.txt");

        assertThat(resolved).isEqualTo(validFile.toRealPath());
    }

    @Test
    @DisplayName("Given non-existent file in valid directory - when resolved - then returns normalized path without error")
    void testNonExistentFileInValidBoundaryResolves() {
        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);

        Path resolved = validator.validateAndResolve("subdir/new-file.txt");

        assertThat(resolved.toString()).startsWith(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("Given null or blank path - throws SecurityException")
    void testNullOrBlankPathThrowsSecurityException() {
        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);
        assertThatThrownBy(() -> validator.validateAndResolve(null))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> validator.validateAndResolve("   "))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> validator.validateForWrite(""))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Given path traversal attempt outside root - when resolved - then throws SecurityException")
    void testPathTraversalThrowsSecurityException() {
        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);

        assertThatThrownBy(() -> validator.validateAndResolve("../../etc/passwd"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Jailbreak attempt");
    }

    @Test
    @DisplayName("Given write validation targeting .git directory - when checked - then throws SecurityException")
    void testWriteToGitDirectoryForbidden() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);

        assertThatThrownBy(() -> validator.validateForWrite(".git/config"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Protected location");

        assertThatThrownBy(() -> validator.validateForWrite(".git"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Protected location");
    }

    @Test
    @DisplayName("Given valid file for write - when checked - then returns validated RepoPath")
    void testValidateForWriteSuccess() throws IOException {
        Path validFile = Files.createFile(tempDir.resolve("valid.yaml"));
        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);

        RepoPath resolved = validator.validateForWrite("valid.yaml");

        assertThat(resolved.toPath()).isEqualTo(validFile.toRealPath());
        assertThat(validator.getActiveWorkingRoot()).isEqualTo(tempDir);
    }

    @Test
    @DisplayName("Given symbolic link target - when validateForWrite checked - then throws SecurityException")
    void testValidateForWriteRejectsSymbolicLink() throws IOException {
        Path targetFile = Files.createFile(tempDir.resolve("target.txt"));
        Path symlink = tempDir.resolve("symlink.txt");
        Files.createSymbolicLink(symlink, targetFile);

        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);

        assertThatThrownBy(() -> validator.validateForWrite("symlink.txt"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Refusing to write through symbolic link");
    }

    @Test
    @DisplayName("Given default constructor - when created - uses user.dir")
    void testDefaultConstructor() {
        WorkspaceJailValidator validator = new WorkspaceJailValidator();

        assertThat(validator.getActiveWorkingRoot()).isEqualTo(Path.of(System.getProperty("user.dir")));
    }

    @Test
    @DisplayName("Given symlinked ancestor directory - when validateAndResolve or validateForWrite checked - then throws SecurityException")
    void testValidateRejectsSymlinkedAncestorDirectory() throws IOException {
        Path realDir = Files.createDirectories(tempDir.resolve("real_dir"));
        Path symlinkDir = tempDir.resolve("sym_dir");
        Files.createSymbolicLink(symlinkDir, realDir);

        WorkspaceJailValidator validator = new WorkspaceJailValidator(tempDir);

        assertThatThrownBy(() -> validator.validateAndResolve("sym_dir/file.txt"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Symbolic links are forbidden");

        assertThatThrownBy(() -> validator.validateForWrite("sym_dir/file.txt"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Symbolic links are forbidden");
    }

    @Test
    @DisplayName("Given non-existent activeWorkingRoot - falls back to absolute normalized path")
    void testNonExistentActiveWorkingRoot() {
        Path nonExistentRoot = tempDir.resolve("non_existent_workspace_root");
        WorkspaceJailValidator validator = new WorkspaceJailValidator(nonExistentRoot);

        Path resolved = validator.validateAndResolve("file.txt");
        assertThat(resolved).isEqualTo(nonExistentRoot.toAbsolutePath().normalize().resolve("file.txt"));
    }
}
