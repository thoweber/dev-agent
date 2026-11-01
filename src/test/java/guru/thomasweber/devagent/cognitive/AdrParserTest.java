package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.segment.TextSegment;
import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.RepoPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdrParserTest {

    @TempDir
    Path tempDir;

    private AdrParser parser;
    private RepoPath repoRoot;

    @BeforeEach
    void setUp() {
        parser = new AdrParser(new MarkdownParserService());
        repoRoot = RepoPath.of(tempDir, tempDir);
    }

    @Test
    @DisplayName("Given ADR with YAML frontmatter - when parseRecord - then extracts all metadata accurately")
    void givenAdrWithFrontmatter_whenParseRecord_thenExtractsMetadata() throws IOException {
        Path adrFile = tempDir.resolve("docs/adr/ADR-042-commonmark.md");
        Files.createDirectories(adrFile.getParent());
        Files.writeString(adrFile, """
            ---
            id: ADR-042
            title: Native CommonMark AST Parsing
            status: ACCEPTED
            summary: Replace regex markdown parsing with commonmark AST
            tags:
              - parser
              - ast
            ---
            # Body Heading

            Body content here.
            """);

        RepoPath repoPath = RepoPath.of(adrFile, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.id()).isEqualTo("ADR-042");
        assertThat(record.title()).isEqualTo("Native CommonMark AST Parsing");
        assertThat(record.status()).isEqualTo("ACCEPTED");
        assertThat(record.summary()).isEqualTo("Replace regex markdown parsing with commonmark AST");
        assertThat(record.tags()).contains("parser", "ast", "adr");
    }

    @Test
    @DisplayName("Given ADR without frontmatter - when parseRecord - then extracts ID and title from content and filename")
    void givenAdrWithoutFrontmatter_whenParseRecord_thenExtractsFromContent() throws IOException {
        Path adrFile = tempDir.resolve("docs/adr/ADR-001-picocli.md");
        Files.createDirectories(adrFile.getParent());
        Files.writeString(adrFile, """
            # ADR-001: Quarkus Picocli Native CLI
            * **Status:** Active
            
            ## Summary
            Ahead-of-Time native binary targeting sub-50ms cold boot via GraalVM Mandrel.
            
            ## Decision
            Picocli chosen for CLI parsing.
            """);

        RepoPath repoPath = RepoPath.of(adrFile, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.id()).isEqualTo("ADR-001");
        assertThat(record.title()).isEqualTo("ADR-001: Quarkus Picocli Native CLI");
        assertThat(record.status()).isEqualTo("Active");
        assertThat(record.summary()).contains("Ahead-of-Time native binary");
        assertThat(record.tags()).contains("adr");
    }

    @Test
    @DisplayName("Given document with status on first line of preamble - when parseRecord - then summary takes first non-status line")
    void givenStatusOnFirstLine_whenParseRecord_thenSummaryFiltersOutStatusLine() throws IOException {
        Path adrFile = tempDir.resolve(".agents/rules/01-rule.md");
        Files.createDirectories(adrFile.getParent());
        Files.writeString(adrFile, """
            # Architecture Rule

            status: Active
            Actual summary description of the architectural rule.
            Additional details.
            """);

        RepoPath repoPath = RepoPath.of(adrFile, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.id()).isEqualTo("01-rule");
        assertThat(record.title()).isEqualTo("Architecture Rule");
        assertThat(record.status()).isEqualTo("Active");
        assertThat(record.summary()).isEqualTo("Actual summary description of the architectural rule.");
        assertThat(record.tags()).contains("rule");
    }

    @Test
    @DisplayName("Given header with embedded ID - when parseRecord - then extracts header ID")
    void givenHeaderWithEmbeddedId_whenParseRecord_thenExtractsHeaderId() throws IOException {
        Path file = tempDir.resolve("guidelines.md");
        Files.writeString(file, """
            # REM-05: Entity Encapsulation
            
            Guideline content.
            """);

        RepoPath repoPath = RepoPath.of(file, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.id()).isEqualTo("REM-05");
        assertThat(record.title()).isEqualTo("REM-05: Entity Encapsulation");
    }

    @Test
    @DisplayName("Given document without any ID pattern - when parseRecord - then falls back to filename without extension")
    void givenDocWithoutId_whenParseRecord_thenFallsBackToFilename() throws IOException {
        Path file = tempDir.resolve("coding-standards.md");
        Files.writeString(file, """
            # Coding Standards
            
            Team coding standards.
            """);

        RepoPath repoPath = RepoPath.of(file, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.id()).isEqualTo("coding-standards");
        assertThat(record.title()).isEqualTo("Coding Standards");
    }

    @Test
    @DisplayName("Given non-existent file - when parseRecord - then throws UncheckedIOException")
    void givenNonExistentFile_whenParseRecord_thenThrowsUncheckedIOException() {
        Path nonExistent = tempDir.resolve("missing.md");
        RepoPath repoPath = RepoPath.of(nonExistent, tempDir);

        assertThatThrownBy(() -> parser.parseRecord(repoPath))
            .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("Given markdown content - when parseSegments - then creates TextSegments with metadata")
    void givenMarkdownContent_whenParseSegments_thenCreatesTextSegments() {
        String content = """
            ---
            id: ADR-10
            title: Modulith Arch
            ---
            ## Context
            Context background.

            ## Decision
            Decision details.
            """;

        List<TextSegment> segments = parser.parseSegments(Path.of("docs/adr/ADR-10.md"), content);

        assertThat(segments).hasSize(2);
        assertThat(segments.getFirst().metadata().getString("id")).isEqualTo("ADR-10");
        assertThat(segments.getFirst().metadata().getString("title")).isEqualTo("Modulith Arch");
        assertThat(segments.getFirst().metadata().getString("section")).isEqualTo("Context");
        assertThat(segments.getFirst().text()).contains("[ADR-10 - Modulith Arch] Context");
    }

    @Test
    @DisplayName("Given null filePath or null content - when parseSegments - then handles gracefully")
    void givenNullFilePathOrContent_whenParseSegments_thenHandlesGracefully() {
        List<TextSegment> segments1 = parser.parseSegments(null, null);
        List<TextSegment> segments2 = parser.parseSegments(null, "## Heading\n\nContent");

        assertThat(segments1).isEmpty();
        assertThat(segments2).hasSize(1);
        assertThat(segments2.getFirst().metadata().getString("id")).isEqualTo("unknown.md");
        assertThat(segments2.getFirst().metadata().getString("path")).isEmpty();
    }

    @Test
    @DisplayName("Given blank frontmatter metadata - when parseRecord - then falls back to content extraction")
    void givenBlankFrontmatterMetadata_whenParseRecord_thenFallsBackToContent() throws IOException {
        Path file = tempDir.resolve("docs/adr/ADR-099-edge.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            ---
            id: "   "
            title: ""
            status: "   "
            summary: "   "
            ---
            ## Context
            Context background for ADR 99.
            """);

        RepoPath repoPath = RepoPath.of(file, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.id()).isEqualTo("ADR-099");
        assertThat(record.title()).isEqualTo("Context");
        assertThat(record.status()).isEqualTo("Active");
        assertThat(record.summary()).isEqualTo("Context background for ADR 99.");
    }

    @Test
    @DisplayName("Given document with no headings - when parseRecord - then title falls back to filename")
    void givenNoHeadings_whenParseRecord_thenTitleFallsBackToFilename() throws IOException {
        Path file = tempDir.resolve("plain-note.md");
        Files.writeString(file, "Just plain text without any markdown headings.");

        RepoPath repoPath = RepoPath.of(file, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.id()).isEqualTo("plain-note");
        assertThat(record.title()).isEqualTo("plain-note");
        assertThat(record.summary()).isEqualTo("Just plain text without any markdown headings.");
    }

    @Test
    @DisplayName("Given document with only status lines and no other content - when parseRecord - then summary falls back to title")
    void givenOnlyStatusLine_whenParseRecord_thenSummaryFallsBackToTitle() throws IOException {
        Path file = tempDir.resolve("empty-body.md");
        Files.writeString(file, """
            # Empty Body Record
            status: Draft
            """);

        RepoPath repoPath = RepoPath.of(file, tempDir);
        ArchitecturalRecord record = parser.parseRecord(repoPath);

        assertThat(record.title()).isEqualTo("Empty Body Record");
        assertThat(record.status()).isEqualTo("Draft");
        assertThat(record.summary()).isEqualTo("Empty Body Record");
    }

    @Test
    @DisplayName("Given markdown content without frontmatter - when parseSegments - then derives metadata from AST")
    void givenContentWithoutFrontmatter_whenParseSegments_thenDerivesFromAst() {
        String content = """
            # Architecture Guide
            
            ## Overview
            Overview of the architecture.
            """;

        List<TextSegment> segments = parser.parseSegments(Path.of("docs/guide.md"), content);

        assertThat(segments).hasSize(2);
        assertThat(segments.getFirst().metadata().getString("id")).isEqualTo("guide.md");
        assertThat(segments.getFirst().metadata().getString("title")).isEqualTo("Architecture Guide");
        assertThat(segments.get(1).metadata().getString("section")).isEqualTo("Overview");
    }

    @Test
    @DisplayName("Given default constructor - when instantiated - then initialized successfully")
    void givenDefaultConstructor_whenInstantiated_thenNotNull() {
        AdrParser defaultParser = new AdrParser();
        assertThat(defaultParser).isNotNull();
    }
}
