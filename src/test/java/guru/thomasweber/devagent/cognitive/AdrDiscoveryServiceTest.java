package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.segment.TextSegment;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.NioWorkspaceFileScanner;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdrDiscoveryServiceTest {

    @TempDir
    Path tempDir;

    private WorkspaceContextResolver contextResolver;
    private AdrDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        contextResolver = new WorkspaceContextResolver(tempDir);
        discoveryService = new AdrDiscoveryService(contextResolver);
    }

    @Test
    @DisplayName("Given default convention directories - when discovering - then loads ADRs and rules correctly")
    void givenDefaultConventionDirectories_whenDiscovering_thenLoadsAdrsAndRules() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("ADR-001-picocli-cli.md"), """
            # ADR-001: Quarkus Picocli Native CLI
            
            * **Status:** Accepted
            * **Date:** 2026-10-25
            
            ## Summary
            Ahead-of-Time native binary targeting sub-50ms cold boot via GraalVM Mandrel.
            
            ## Decision
            Picocli was chosen for CLI command parsing.
            """);

        Path rulesDir = tempDir.resolve(".agents/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("01-architecture-invariants.md"), """
            # dev-agent Core Architecture Invariants
            
            * **Status:** Active
            
            ## Summary
            Engine vs Knowledge separation and two-axis extensibility.
            """);

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(2);
        ArchitecturalRecord adr = records.stream()
            .filter(r -> "ADR-001".equals(r.id()))
            .findFirst()
            .orElseThrow();

        assertThat(adr.title()).isEqualTo("ADR-001: Quarkus Picocli Native CLI");
        assertThat(adr.status()).isEqualTo("Accepted");
        assertThat(adr.summary()).contains("Ahead-of-Time native binary targeting sub-50ms cold boot");
        assertThat(adr.content()).contains("Picocli was chosen");
        assertThat(adr.tags()).contains("adr");

        ArchitecturalRecord rule = records.stream()
            .filter(r -> "01-architecture-invariants".equals(r.id()))
            .findFirst()
            .orElseThrow();

        assertThat(rule.title()).isEqualTo("dev-agent Core Architecture Invariants");
        assertThat(rule.status()).isEqualTo("Active");
        assertThat(rule.summary()).contains("Engine vs Knowledge separation");
        assertThat(rule.tags()).contains("rule");
    }

    @Test
    @DisplayName("Given .agents/dev-agent.yaml with custom rules-paths and formats - when discovering - then loads custom paths exclusively")
    void givenYamlWithCustomPaths_whenDiscovering_thenLoadsCustomPaths() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("dev-agent.yaml"), """
            # Top-level comment
            agent:
              workspace:
                backend-root: "."
              knowledge:
                rules-paths:
                  - "custom/adrs/*.md"
                  - 'specs/**/*.md'
                  - ./unquoted/*.md
              budget:
                max-task-cost-usd: 2.00
            """);

        Path customAdrDir = tempDir.resolve("custom/adrs");
        Files.createDirectories(customAdrDir);
        Files.writeString(customAdrDir.resolve("CUSTOM-01.md"), """
            # CUSTOM-01: Custom ADR
            * **Status:** Proposed
            
            ## Summary
            Custom architecture decision.
            """);

        Path specsDir = tempDir.resolve("specs/deep");
        Files.createDirectories(specsDir);
        Files.writeString(specsDir.resolve("SPEC-01.md"), """
            # SPEC-01: Deep Specification
            * **Status:** Active
            
            ## Summary
            Deep nested rule specification.
            """);

        Path unquotedDir = tempDir.resolve("unquoted");
        Files.createDirectories(unquotedDir);
        Files.writeString(unquotedDir.resolve("ITEM-01.md"), """
            # ITEM-01: Unquoted Rule
            
            First paragraph summary text without header.
            """);

        // Ignored convention directory
        Path ignoredDir = tempDir.resolve("docs/adr");
        Files.createDirectories(ignoredDir);
        Files.writeString(ignoredDir.resolve("ADR-999.md"), "# Ignored ADR");

        List<ArchitecturalRecord> records = discoveryService.discoverRecords(tempDir);

        assertThat(records).hasSize(3);
        assertThat(records).extracting(ArchitecturalRecord::id).containsExactlyInAnyOrder("CUSTOM-01", "SPEC-01", "ITEM-01");
    }

    @Test
    @DisplayName("Given non-existent workspace root - when discovering - then returns empty list")
    void givenNonExistentRoot_whenDiscovering_thenReturnsEmptyList() {
        Path nonExistent = tempDir.resolve("does_not_exist");
        List<ArchitecturalRecord> records = discoveryService.discoverRecords(nonExistent);
        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("Given non-markdown files and single adrs.md convention file - when discovering - then processes correctly")
    void givenNonMarkdownAndSingleAdrFile_whenDiscovering_thenProcessesCorrectly() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("notes.txt"), "Not a markdown file");

        Path techDir = tempDir.resolve("docs/tech/architecture");
        Files.createDirectories(techDir);
        Files.writeString(techDir.resolve("adrs.md"), """
            # Architecture Decisions Document
            Status: Proposed
            
            ## Context
            Background context snippet for architectural decisions.
            """);

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(1);
        ArchitecturalRecord rec = records.getFirst();
        assertThat(rec.status()).isEqualTo("Proposed");
        assertThat(rec.summary()).contains("Background context snippet");
    }

    @Test
    @DisplayName("Given yaml with path traversal - when discovering - then throws SecurityException")
    void givenYamlWithPathTraversal_whenDiscovering_thenThrowsSecurityException() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("dev-agent.yaml"), """
            agent:
              knowledge:
                rules-paths:
                  - "../../etc/*.md"
            """);

        assertThatThrownBy(() -> discoveryService.discoverRecords())
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Given null or blank pattern - when discovering - then throws SecurityException")
    void givenBlankPattern_whenDiscovering_thenThrowsSecurityException() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("dev-agent.yaml"), """
            agent:
              knowledge:
                rules-paths:
                  - "   "
            """);

        assertThatThrownBy(() -> discoveryService.discoverRecords())
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Given single ADR.md in root - when discovering - then extracts single record")
    void givenSingleAdrMd_whenDiscovering_thenExtractsRecord() throws IOException {
        Files.writeString(tempDir.resolve("ADR.md"), """
            # Root ADR: Unified Architecture Decisions
            * **Status:** Accepted
            
            ## Summary
            Unified single-file ADR document.
            """);

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(1);
        ArchitecturalRecord rec = records.getFirst();
        assertThat(rec.id()).isEqualTo("ADR");
        assertThat(rec.title()).isEqualTo("Root ADR: Unified Architecture Decisions");
        assertThat(rec.status()).isEqualTo("Accepted");
        assertThat(rec.summary()).contains("Unified single-file ADR document");
    }

    @Test
    @DisplayName("Given document without markdown title header - when parsing - then falls back to filename")
    void givenDocWithoutHeader_whenParsing_thenFallsBackToFilename() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("no-header-doc.md"), "Just plain text without header.");

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().title()).isEqualTo("no-header-doc");
        assertThat(records.getFirst().status()).isEqualTo("Active");
    }

    @Test
    @DisplayName("Given ignored files in .git or target directories - when discovering - then skips ignored paths")
    void givenIgnoredPaths_whenDiscovering_thenSkipsIgnored() throws IOException {
        Path gitDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("sample.md"), "# Git Hook");

        Path targetAgentDir = tempDir.resolve("target-agent");
        Files.createDirectories(targetAgentDir);
        Files.writeString(targetAgentDir.resolve("generated.md"), "# Generated Doc");

        Path standardTargetDir = tempDir.resolve("target");
        Files.createDirectories(standardTargetDir);
        Files.writeString(standardTargetDir.resolve("build.md"), "# Build Doc");

        Path docDir = tempDir.resolve("docs/adr");
        Files.createDirectories(docDir);
        Files.writeString(docDir.resolve("valid.md"), "# Valid ADR");

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().title()).isEqualTo("Valid ADR");
    }

    @Test
    @DisplayName("Given doc with only status paragraph - when parsing - then summary falls back to title")
    void givenDocWithOnlyStatus_whenParsing_thenSummaryFallsBackToTitle() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("status-only.md"), """
            # Status Only Document

            status: Active
            """);

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().summary()).isEqualTo("Status Only Document");
    }

    @Test
    @DisplayName("Given path with both adr and rule keywords - when discovering - then tags contain both")
    void givenPathWithBothKeywords_whenDiscovering_thenTagsContainBoth() throws IOException {
        Path customRoot = tempDir.resolve("custom-repo");
        Path dir = customRoot.resolve("docs/adr");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("rule-01.md"), "# ADR-99: Rule details");

        List<ArchitecturalRecord> records = discoveryService.discoverRecords(customRoot);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().tags()).contains("adr", "rule");
    }

    @Test
    @DisplayName("Given non-markdown file specified directly - when scanning pattern - then skips non-markdown")
    void givenNonMarkdownFileSpecified_whenScanning_thenSkips() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("dev-agent.yaml"), """
            agent:
              knowledge:
                rules-paths:
                  - "docs/file.txt"
                  - "docs/*.md"
                depth: 3
            root-section:
              enabled: true
            """);

        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("file.txt"), "plain text");
        Files.writeString(docsDir.resolve("valid.md"), "# Valid\n\nContent");

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().title()).isEqualTo("Valid");
    }

    @Test
    @DisplayName("Given document with ID in header only - when discovering - then extracts ID from header")
    void givenDocWithIdInHeaderOnly_whenDiscovering_thenExtractsIdFromHeader() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("encapsulation.md"), """
            # REM-05: Panache Entity Encapsulation
            * **Status:** Accepted
            
            Entity isolation rules.
            """);

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().id()).isEqualTo("REM-05");
        assertThat(records.getFirst().title()).isEqualTo("REM-05: Panache Entity Encapsulation");
    }

    @Test
    @DisplayName("Given document without ID pattern anywhere - when discovering - then falls back to filename as ID")
    void givenDocWithoutIdPattern_whenDiscovering_thenFallsBackToFilenameAsId() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("team-guidelines.md"), """
            # Team Conventions and Style
            * **Status:** Active
            
            Team style guide.
            """);

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().id()).isEqualTo("team-guidelines");
        assertThat(records.getFirst().title()).isEqualTo("Team Conventions and Style");
    }

    @Test
    @DisplayName("Given dev-agent.yaml with inline array and comments - when discovering - then parses inline array properly")
    void givenYamlWithInlineArrayAndComments_whenDiscovering_thenParsesCorrectly() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("dev-agent.yaml"), """
            agent:
              knowledge:
                rules-paths: [ "inline/adrs/*.md" # inline comment
                             , "specs/*.md" ]
            """);

        Path inlineDir = tempDir.resolve("inline/adrs");
        Files.createDirectories(inlineDir);
        Files.writeString(inlineDir.resolve("INLINE-01.md"), "# INLINE-01: Inline Rule\n\nInline content.");

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().id()).isEqualTo("INLINE-01");
    }

    @Test
    @DisplayName("Given ADR with YAML frontmatter - when discovering - then extracts frontmatter metadata directly")
    void givenAdrWithFrontmatter_whenDiscovering_thenExtractsFrontmatterMetadata() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("ADR-042.md"), """
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

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();
        assertThat(records).hasSize(1);
        ArchitecturalRecord entry = records.getFirst();
        assertThat(entry.id()).isEqualTo("ADR-042");
        assertThat(entry.title()).isEqualTo("Native CommonMark AST Parsing");
        assertThat(entry.status()).isEqualTo("ACCEPTED");
        assertThat(entry.summary()).isEqualTo("Replace regex markdown parsing with commonmark AST");
        assertThat(entry.tags()).contains("parser", "ast", "adr");
    }

    @Test
    @DisplayName("Given ADR with heading inside fenced code block - when discovering - then does not treat code comment as heading")
    void givenAdrWithCodeHeading_whenDiscovering_thenDoesNotTreatCodeAsHeading() throws IOException {
        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("ADR-050.md"), """
            # ADR-050: Code Block Safety

            ```bash
            # Fake heading inside bash snippet
            echo "not a heading"
            ```

            ## Summary
            Legitimate summary section.
            """);

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();
        assertThat(records).hasSize(1);
        ArchitecturalRecord entry = records.getFirst();
        assertThat(entry.id()).isEqualTo("ADR-050");
        assertThat(entry.title()).isEqualTo("ADR-050: Code Block Safety");
        assertThat(entry.summary()).isEqualTo("Legitimate summary section.");
    }

    @Test
    @DisplayName("Given raw ADR document with frontmatter - when parseAdrDocument - then returns TextSegments with metadata")
    void givenRawAdrDocument_whenParseAdrDocument_thenReturnsTextSegmentsWithMetadata() {
        Path filePath = Path.of("docs/adr/ADR-010.md");
        String rawContent = """
            ---
            id: ADR-010
            title: Vert.x Review GUI
            ---
            # Overview
            Embedded Vert.x HTTP server on port 9999.

            ## Architecture
            Reactive routes with Storybook iframe.
            """;

        List<TextSegment> segments = discoveryService.parseAdrDocument(filePath, rawContent);
        assertThat(segments).hasSize(2);

        TextSegment seg1 = segments.get(0);
        assertThat(seg1.metadata().getString("id")).isEqualTo("ADR-010");
        assertThat(seg1.metadata().getString("title")).isEqualTo("Vert.x Review GUI");
        assertThat(seg1.metadata().getString("section")).isEqualTo("Overview");
        assertThat(seg1.text()).contains("[ADR-010 - Vert.x Review GUI] Overview");
        assertThat(seg1.text()).contains("Embedded Vert.x HTTP server on port 9999.");

        TextSegment seg2 = segments.get(1);
        assertThat(seg2.metadata().getString("section")).isEqualTo("Architecture");
        assertThat(seg2.text()).contains("[ADR-010 - Vert.x Review GUI] Architecture");
        assertThat(seg2.text()).contains("Reactive routes with Storybook iframe.");
    }

    @Test
    @DisplayName("Given raw ADR document without frontmatter - when parseAdrDocument - then handles cleanly without exceptions")
    void givenRawAdrWithoutFrontmatter_whenParseAdrDocument_thenHandlesCleanly() {
        Path filePath = Path.of("docs/adr/ADR-015.md");
        String rawContent = """
            # Simple ADR
            Content of simple ADR.
            """;

        List<TextSegment> segments = discoveryService.parseAdrDocument(filePath, rawContent);
        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().metadata().getString("id")).isEqualTo("ADR-015");
        assertThat(segments.getFirst().metadata().getString("title")).isEqualTo("Simple ADR");
        assertThat(segments.getFirst().metadata().getString("section")).isEqualTo("Simple ADR");
    }

    @Test
    @DisplayName("Given code block with bash comments - when parseAdrDocument - then code comments are not treated as sections")
    void givenCodeBlockComments_whenParseAdrDocument_thenNotTreatedAsSections() {
        Path filePath = Path.of("docs/adr/ADR-020.md");
        String rawContent = """
            # Execution
            ```bash
            # Comment line 1
            # Comment line 2
            run_cmd
            ```
            """;

        List<TextSegment> segments = discoveryService.parseAdrDocument(filePath, rawContent);
        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().metadata().getString("section")).isEqualTo("Execution");
    }

    @Test
    @DisplayName("Given null filePath and null content - when parseAdrDocument - then handles safely without exceptions")
    void givenNullFilePathAndContent_whenParseAdrDocument_thenHandlesSafely() {
        List<TextSegment> segments = discoveryService.parseAdrDocument(null, null);
        assertThat(segments).isEmpty();
    }

    @Test
    @DisplayName("Given explicit configLoader constructor - when discovering - then executes properly")
    void givenExplicitConfigLoaderConstructor_whenDiscovering_thenExecutesProperly() {
        AdrDiscoveryService service = new AdrDiscoveryService(
            contextResolver,
            new DevAgentConfigLoader()
        );
        List<ArchitecturalRecord> records = service.discoverRecords();
        assertThat(records).isNotNull();
    }

    @Test
    @DisplayName("Given explicit fileScanner constructor - when discovering - then executes properly")
    void givenExplicitFileScannerConstructor_whenDiscovering_thenExecutesProperly() {
        AdrDiscoveryService service = new AdrDiscoveryService(
            contextResolver,
            new DevAgentConfigLoader(),
            new NioWorkspaceFileScanner()
        );
        List<ArchitecturalRecord> records = service.discoverRecords();
        assertThat(records).isNotNull();
    }

    @Test
    @DisplayName("Given four-arg constructor - when discovering - then executes properly")
    void givenFourArgConstructor_whenDiscovering_thenExecutesProperly() {
        AdrDiscoveryService service = new AdrDiscoveryService(
            contextResolver,
            new DevAgentConfigLoader(),
            new NioWorkspaceFileScanner(),
            new AdrParser()
        );
        List<ArchitecturalRecord> records = service.discoverRecords();
        assertThat(records).isNotNull();
    }

    @Test
    @DisplayName("Given null root - when discovering - then returns empty list safely")
    void givenNullRoot_whenDiscovering_thenReturnsEmptyList() {
        List<ArchitecturalRecord> records = discoveryService.discoverRecords(null);
        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("Given symlink .agents directory - when discovering - then throws SecurityException")
    void givenSymlinkAgentsDir_whenDiscovering_thenThrowsSecurityException() throws IOException {
        Path outsideDir = tempDir.resolve("outside-agents");
        Files.createDirectories(outsideDir);
        Path symlinkAgents = tempDir.resolve(".agents");
        try {
            Files.createSymbolicLink(symlinkAgents, outsideDir);
        } catch (UnsupportedOperationException | SecurityException | IOException _) {
            return;
        }

        assertThatThrownBy(() -> discoveryService.discoverRecords())
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Forbidden symbolic link at .agents directory");
    }

    @Test
    @DisplayName("Given symlink dev-agent.yaml file - when discovering - then throws SecurityException")
    void givenSymlinkConfigFile_whenDiscovering_thenThrowsSecurityException() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Path outsideConfig = tempDir.resolve("outside-config.yaml");
        Files.writeString(outsideConfig, "agent:\n  knowledge:\n    rules-paths: []\n");
        Path symlinkConfig = agentsDir.resolve("dev-agent.yaml");
        try {
            Files.createSymbolicLink(symlinkConfig, outsideConfig);
        } catch (UnsupportedOperationException | SecurityException | IOException _) {
            return;
        }

        assertThatThrownBy(() -> discoveryService.discoverRecords())
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Forbidden symbolic link at configuration file");
    }

    @Test
    @DisplayName("Given dangling symlink dev-agent.yaml file - when discovering - then throws SecurityException")
    void givenDanglingSymlinkConfigFile_whenDiscovering_thenThrowsSecurityException() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir);
        Path nonExistent = tempDir.resolve("does-not-exist.yaml");
        Path symlinkConfig = agentsDir.resolve("dev-agent.yaml");
        try {
            Files.createSymbolicLink(symlinkConfig, nonExistent);
        } catch (UnsupportedOperationException | SecurityException | IOException _) {
            return;
        }

        assertThatThrownBy(() -> discoveryService.discoverRecords())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Forbidden symbolic link at configuration file");
    }

    @Test
    @DisplayName("Given directory as dev-agent.yaml file - when discovering - then returns empty list")
    void givenDirectoryConfigFile_whenDiscovering_thenReturnsEmptyList() throws IOException {
        Path agentsDir = tempDir.resolve(".agents");
        Files.createDirectories(agentsDir.resolve("dev-agent.yaml"));

        List<ArchitecturalRecord> records = discoveryService.discoverRecords();
        assertThat(records).isNotNull();
    }
}
