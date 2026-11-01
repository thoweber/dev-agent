package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorkspaceJailValidator;
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
    private WorkspaceJailValidator jailValidator;
    private AdrDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        contextResolver = new WorkspaceContextResolver(tempDir);
        jailValidator = new WorkspaceJailValidator(tempDir);
        discoveryService = new AdrDiscoveryService(contextResolver, jailValidator);
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
                  - "unbalanced
                  - 'unbalanced
                  - a
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
        ArchitecturalRecord record = records.getFirst();
        assertThat(record.status()).isEqualTo("Proposed");
        assertThat(record.summary()).contains("Background context snippet");
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
                  - ""
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
        ArchitecturalRecord record = records.getFirst();
        assertThat(record.id()).isEqualTo("ADR");
        assertThat(record.title()).isEqualTo("Root ADR: Unified Architecture Decisions");
        assertThat(record.status()).isEqualTo("Accepted");
        assertThat(record.summary()).contains("Unified single-file ADR document");
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
}
