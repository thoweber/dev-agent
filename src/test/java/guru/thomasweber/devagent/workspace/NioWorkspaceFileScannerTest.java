package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NioWorkspaceFileScannerTest {

    @TempDir
    Path tempDir;

    private RepoPath repoRoot;
    private NioWorkspaceFileScanner scanner;

    @BeforeEach
    void setUp() {
        repoRoot = RepoPath.of(tempDir, tempDir);
        scanner = new NioWorkspaceFileScanner();
    }

    @Test
    @DisplayName("Given glob pattern - when findFiles - then matches recursively and maps to RepoPath")
    void givenGlobPattern_whenFindFiles_thenMatchesRecursively() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("ADR-001.md"), "# ADR 1");
        Files.writeString(adrDir.resolve("ignore.txt"), "text");

        Path specsDir = tempDir.resolve("docs/specs/nested");
        Files.createDirectories(specsDir);
        Files.writeString(specsDir.resolve("SPEC-01.md"), "# Spec 1");

        List<RepoPath> results = scanner.findFiles(repoRoot, "docs/**/*.md");

        assertThat(results)
            .extracting(r -> r.relativize().toString().replace('\\', '/'))
            .containsExactlyInAnyOrder("docs/adr/ADR-001.md", "docs/specs/nested/SPEC-01.md");
    }

    @Test
    @DisplayName("Given directory pattern - when findFiles - then discovers all files within directory")
    void givenDirectoryPattern_whenFindFiles_thenDiscoversAllFilesInside() throws IOException {
        Path rulesDir = tempDir.resolve(".agents/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("01-rule.md"), "# Rule 1");
        Files.writeString(rulesDir.resolve("02-rule.md"), "# Rule 2");

        List<RepoPath> results = scanner.findFiles(repoRoot, ".agents/rules");

        assertThat(results)
            .extracting(r -> r.relativize().toString().replace('\\', '/'))
            .containsExactlyInAnyOrder(".agents/rules/01-rule.md", ".agents/rules/02-rule.md");
    }

    @Test
    @DisplayName("Given single file pattern - when findFiles - then returns single matching RepoPath")
    void givenSingleFilePattern_whenFindFiles_thenReturnsSingleFile() throws IOException {
        Files.writeString(tempDir.resolve("ADR.md"), "# Root ADR");

        List<RepoPath> results = scanner.findFiles(repoRoot, "ADR.md");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().relativize().toString().replace('\\', '/')).isEqualTo("ADR.md");
    }

    @Test
    @DisplayName("Given ignored prefixes - when findFiles - then skips matching subtrees completely")
    void givenIgnoredPrefixes_whenFindFiles_thenSkipsSubtrees() throws IOException {
        Path targetDir = tempDir.resolve("target-agent/docs");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("generated.md"), "# Generated");

        Path gitDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("hook.md"), "# Hook");

        Path validDir = tempDir.resolve("docs");
        Files.createDirectories(validDir);
        Files.writeString(validDir.resolve("valid.md"), "# Valid");

        List<RepoPath> results = scanner.findFiles(repoRoot, "**/*.md", WorkspaceFileScanner.DEFAULT_IGNORED_PREFIXES);

        assertThat(results)
            .extracting(r -> r.relativize().toString().replace('\\', '/'))
            .containsExactly("docs/valid.md");
    }

    @Test
    @DisplayName("Given custom file filter - when findFiles - then applies filter strictly")
    void givenFileFilter_whenFindFiles_thenAppliesFilter() throws IOException {
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("file.txt"), "text file");
        Files.writeString(docsDir.resolve("file.md"), "# markdown file");

        List<RepoPath> results = scanner.findFiles(
            repoRoot,
            List.of("docs"),
            Set.of(),
            p -> p.getFileName().toString().endsWith(".md")
        );

        assertThat(results)
            .extracting(r -> r.relativize().toString().replace('\\', '/'))
            .containsExactly("docs/file.md");
    }

    @Test
    @DisplayName("Given path traversal segment - when findFiles - then throws SecurityException")
    void givenTraversalPattern_whenFindFiles_thenThrowsSecurityException() {
        assertThatThrownBy(() -> scanner.findFiles(repoRoot, "../../secret/*.md"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Traversal detected");

        assertThatThrownBy(() -> scanner.findFiles(repoRoot, "docs/../outside.md"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Traversal detected");
    }

    @Test
    @DisplayName("Given null or blank pattern - when findFiles - then throws SecurityException")
    void givenNullOrBlankPattern_whenFindFiles_thenThrowsSecurityException() {
        assertThatThrownBy(() -> scanner.findFiles(repoRoot, (String) null))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("pattern cannot be null or blank");

        assertThatThrownBy(() -> scanner.findFiles(repoRoot, "   "))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("pattern cannot be null or blank");
    }

    @Test
    @DisplayName("Given non-existent search root prefix - when findFiles - then returns empty list")
    void givenNonExistentSearchRoot_whenFindFiles_thenReturnsEmptyList() {
        List<RepoPath> results = scanner.findFiles(repoRoot, "missing-dir/**/*.md");
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Given multiple overlapping patterns - when findFiles - then deduplicates results preserving order")
    void givenMultiplePatterns_whenFindFiles_thenDeduplicatesResults() throws IOException {
        Path docsDir = tempDir.resolve("docs/adr");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("ADR-001.md"), "# ADR 1");

        List<RepoPath> results = scanner.findFiles(
            repoRoot,
            List.of("docs/adr", "docs/adr/*.md", "docs/**/*.md"),
            Set.of()
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().relativize().toString().replace('\\', '/')).isEqualTo("docs/adr/ADR-001.md");
    }

    @Test
    @DisplayName("Given convenience overload - when findFiles - then uses default ignored prefixes")
    void givenConvenienceOverload_whenFindFiles_thenUsesDefaults() throws IOException {
        Path validDir = tempDir.resolve("docs");
        Files.createDirectories(validDir);
        Files.writeString(validDir.resolve("valid.md"), "# Valid");

        List<RepoPath> results = scanner.findFiles(repoRoot, "docs/*.md");
        assertThat(results).hasSize(1);
    }
}
