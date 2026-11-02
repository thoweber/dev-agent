package guru.thomasweber.devagent.model;

import guru.thomasweber.devagent.workspace.RepoPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitecturalRecordTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid parameters - when building ArchitecturalRecord - then creates expected immutable record")
    void givenValidParameters_whenBuilding_thenCreatesRecord() {
        RepoPath path = RepoPath.of(tempDir.resolve("docs/adr/ADR-001.md"), tempDir);
        ArchitecturalRecord rec = ArchitecturalRecord.builder()
            .id("ADR-001")
            .title("Quarkus Picocli Native CLI")
            .status("Accepted")
            .tags(List.of("cli", "native"))
            .path(path)
            .summary("Ahead-of-Time native binary targeting sub-50ms cold boot.")
            .content("# ADR-001: Quarkus Picocli Native CLI\n\nFull body content.")
            .build();

        assertThat(rec.id()).isEqualTo("ADR-001");
        assertThat(rec.title()).isEqualTo("Quarkus Picocli Native CLI");
        assertThat(rec.status()).isEqualTo("Accepted");
        assertThat(rec.tags()).containsExactly("cli", "native");
        assertThat(rec.path()).isEqualTo(path);
        assertThat(rec.summary()).isEqualTo("Ahead-of-Time native binary targeting sub-50ms cold boot.");
        assertThat(rec.content()).contains("Full body content.");
    }

    @Test
    @DisplayName("Given existing ArchitecturalRecord - when calling toBuilder - then copies fields cleanly")
    void givenExistingRecord_whenCallingToBuilder_thenCopiesFields() {
        RepoPath path = RepoPath.of(tempDir.resolve("docs/adr/ADR-001.md"), tempDir);
        ArchitecturalRecord original = ArchitecturalRecord.builder()
            .id("ADR-001")
            .title("Quarkus Picocli Native CLI")
            .status("Accepted")
            .tags(List.of("cli"))
            .path(path)
            .summary("Summary")
            .content("Content")
            .build();

        ArchitecturalRecord updated = original.toBuilder()
            .status("Deprecated")
            .build();

        assertThat(updated.id()).isEqualTo("ADR-001");
        assertThat(updated.status()).isEqualTo("Deprecated");
        assertThat(updated.title()).isEqualTo(original.title());
        assertThat(updated.path()).isEqualTo(original.path());
        assertThat(original.status()).isEqualTo("Accepted");
    }
}
