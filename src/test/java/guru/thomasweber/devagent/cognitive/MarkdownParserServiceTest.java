package guru.thomasweber.devagent.cognitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownParserServiceTest {

    private final MarkdownParserService parserService = new MarkdownParserService();

    @Test
    @DisplayName("Given null or blank raw markdown - when parsing - then returns empty metadata and sections")
    void givenNullOrBlankMarkdown_whenParsing_thenReturnsEmpty() {
        MarkdownParserService.ParsedMarkdown nullResult = parserService.parse(null);
        assertThat(nullResult.metadata()).isEmpty();
        assertThat(nullResult.sections()).isEmpty();

        MarkdownParserService.ParsedMarkdown blankResult = parserService.parse("   \n\t  ");
        assertThat(blankResult.metadata()).isEmpty();
        assertThat(blankResult.sections()).isEmpty();
    }

    @Test
    @DisplayName("Given markdown with YAML frontmatter - when parsing - then extracts structured metadata")
    void givenMarkdownWithFrontmatter_whenParsing_thenExtractsMetadata() {
        String markdown = """
            ---
            id: ADR-042
            title: Native CommonMark Parser
            status: ACCEPTED
            tags:
              - parser
              - ast
            ---
            # Architectural Context

            We adopt commonmark-java for pure Java AST parsing.
            """;

        MarkdownParserService.ParsedMarkdown parsed = parserService.parse(markdown);

        assertThat(parsed.metadata())
            .containsEntry("id", List.of("ADR-042"))
            .containsEntry("title", List.of("Native CommonMark Parser"))
            .containsEntry("status", List.of("ACCEPTED"));

        assertThat(parsed.metadata().get("tags")).containsExactly("parser", "ast");

        assertThat(parsed.sections()).hasSize(1);
        MarkdownParserService.DocumentSection section = parsed.sections().getFirst();
        assertThat(section.heading()).isEqualTo("Architectural Context");
        assertThat(section.content()).contains("We adopt commonmark-java for pure Java AST parsing.");
    }

    @Test
    @DisplayName("Given markdown without frontmatter - when parsing - then extracts sections without error")
    void givenMarkdownWithoutFrontmatter_whenParsing_thenExtractsSectionsSuccessfully() {
        String markdown = """
            # Executive Summary

            This document has no YAML frontmatter header.

            ## Implementation Details

            Section content goes here.
            """;

        MarkdownParserService.ParsedMarkdown parsed = parserService.parse(markdown);

        assertThat(parsed.metadata()).isEmpty();
        assertThat(parsed.sections()).hasSize(2);
        assertThat(parsed.sections().get(0).heading()).isEqualTo("Executive Summary");
        assertThat(parsed.sections().get(0).content()).contains("This document has no YAML frontmatter header.");
        assertThat(parsed.sections().get(1).heading()).isEqualTo("Implementation Details");
        assertThat(parsed.sections().get(1).content()).contains("Section content goes here.");
    }

    @Test
    @DisplayName("Given heading syntax inside fenced code block - when parsing - then heading is ignored and not treated as a section")
    void givenHeadingInsideFencedCodeBlock_whenParsing_thenIgnoredAsSection() {
        String markdown = """
            # Setup Instructions

            Run the following shell script to initialize:

            ```bash
            # This is a bash comment, not a markdown heading
            # Another comment
            mvn clean package
            ```

            Final text after code block.
            """;

        MarkdownParserService.ParsedMarkdown parsed = parserService.parse(markdown);

        // Should ONLY have one section ("Setup Instructions"), not three sections!
        assertThat(parsed.sections()).hasSize(1);
        MarkdownParserService.DocumentSection section = parsed.sections().getFirst();
        assertThat(section.heading()).isEqualTo("Setup Instructions");
        assertThat(section.content()).contains("Run the following shell script");
        assertThat(section.content()).contains("# This is a bash comment");
        assertThat(section.content()).contains("Final text after code block.");
    }

    @Test
    @DisplayName("Given preamble text before first heading - when parsing - then assigns Preamble heading")
    void givenPreambleText_whenParsing_thenAssignsPreambleHeading() {
        String markdown = """
            Preamble intro text before any heading appears.

            # First Heading
            Body of first heading.
            """;

        MarkdownParserService.ParsedMarkdown parsed = parserService.parse(markdown);

        assertThat(parsed.sections()).hasSize(2);
        assertThat(parsed.sections().get(0).heading()).isEqualTo("Preamble");
        assertThat(parsed.sections().get(0).content()).contains("Preamble intro text");
        assertThat(parsed.sections().get(1).heading()).isEqualTo("First Heading");
        assertThat(parsed.sections().get(1).content()).contains("Body of first heading.");
    }

    @Test
    @DisplayName("Given blockquotes and inline code - when parsing - then formats and preserves literals")
    void givenBlockquotesAndInlineCode_whenParsing_thenPreservesContent() {
        String markdown = """
            # Invariants Review

            > [!IMPORTANT]
            > Security invariant check: `WorkspaceJailValidator` must pass.
            """;

        MarkdownParserService.ParsedMarkdown parsed = parserService.parse(markdown);

        assertThat(parsed.sections()).hasSize(1);
        assertThat(parsed.sections().getFirst().content())
            .contains(">")
            .contains("WorkspaceJailValidator");
    }

    @Test
    @DisplayName("Given null collections in ParsedMarkdown - when constructing - then falls back to empty immutable collections")
    void givenNullCollections_whenConstructingParsedMarkdown_thenDefaultsToEmpty() {
        MarkdownParserService.ParsedMarkdown parsed = new MarkdownParserService.ParsedMarkdown(null, null);
        assertThat(parsed.metadata()).isEmpty();
        assertThat(parsed.sections()).isEmpty();
    }

    @Test
    @DisplayName("Given heading containing inline code - when parsing - then extracts full heading text")
    void givenHeadingWithInlineCode_whenParsing_thenExtractsCodeText() {
        String markdown = """
            # The `WorkspaceJailValidator` Component
            Body text.
            """;

        MarkdownParserService.ParsedMarkdown parsed = parserService.parse(markdown);
        assertThat(parsed.sections()).hasSize(1);
        assertThat(parsed.sections().getFirst().heading()).isEqualTo("The WorkspaceJailValidator Component");
    }
}
