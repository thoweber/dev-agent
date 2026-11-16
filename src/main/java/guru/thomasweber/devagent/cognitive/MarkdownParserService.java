package guru.thomasweber.devagent.cognitive;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.CustomNode;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Thread-safe AST-based Markdown parser service utilizing commonmark-java.
 * Extracts YAML frontmatter metadata and performs robust structural heading-based
 * document segmentation without regex brittleness or fenced code block leaks.
 */
@ApplicationScoped
public class MarkdownParserService {

    private final Parser parser;

    public MarkdownParserService() {
        this.parser = Parser.builder()
            .extensions(List.of(
                YamlFrontMatterExtension.create(),
                TablesExtension.create()
            ))
            .build();
    }

    /**
     * Parses raw Markdown content into extracted YAML metadata and structural sections.
     *
     * @param rawMarkdown the raw markdown string
     * @return structured parsed markdown containing metadata and atomic document sections
     */
    public ParsedMarkdown parse(String rawMarkdown) {
        if (isNull(rawMarkdown) || rawMarkdown.isBlank()) {
            return new ParsedMarkdown(Map.of(), List.of());
        }

        Node document = parser.parse(rawMarkdown);

        // 1. Extract YAML frontmatter preserving declaration order
        YamlFrontMatterVisitor frontMatterVisitor = new YamlFrontMatterVisitor();
        document.accept(frontMatterVisitor);
        Map<String, List<String>> rawMetadata = frontMatterVisitor.getData();
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        if (nonNull(rawMetadata)) {
            for (Map.Entry<String, List<String>> entry : rawMetadata.entrySet()) {
                if (nonNull(entry.getKey()) && nonNull(entry.getValue())) {
                    metadata.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            }
        }

        // 2. Extract structural chunks based on AST Headings
        List<DocumentSection> sections = new ArrayList<>();
        SectionExtractionVisitor sectionVisitor = new SectionExtractionVisitor(sections);
        document.accept(sectionVisitor);
        sectionVisitor.flush();

        return new ParsedMarkdown(metadata, sections);
    }

    private static class SectionExtractionVisitor extends AbstractVisitor {

        private final List<DocumentSection> sections;
        private String currentHeading = "Preamble";
        private boolean hasHeading = false;
        private final StringBuilder buffer = new StringBuilder();

        SectionExtractionVisitor(List<DocumentSection> sections) {
            this.sections = sections;
        }

        @Override
        public void visit(Heading heading) {
            flush();
            currentHeading = extractNodeText(heading);
            hasHeading = true;
        }

        @Override
        public void visit(Text text) {
            buffer.append(text.getLiteral());
            super.visit(text);
        }

        @Override
        public void visit(Code code) {
            buffer.append(code.getLiteral());
            super.visit(code);
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            buffer.append("\n").append(codeBlock.getLiteral()).append("\n");
            super.visit(codeBlock);
        }

        @Override
        public void visit(IndentedCodeBlock indentedCodeBlock) {
            buffer.append("\n").append(indentedCodeBlock.getLiteral()).append("\n");
            super.visit(indentedCodeBlock);
        }

        @Override
        public void visit(HtmlBlock htmlBlock) {
            buffer.append("\n").append(htmlBlock.getLiteral()).append("\n");
            super.visit(htmlBlock);
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            buffer.append("> ");
            super.visit(blockQuote);
        }

        @Override
        public void visit(ListItem listItem) {
            buffer.append("- ");
            super.visit(listItem);
            buffer.append("\n");
        }

        @Override
        public void visit(Paragraph paragraph) {
            super.visit(paragraph);
            buffer.append("\n\n");
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            buffer.append("\n");
            super.visit(softLineBreak);
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            buffer.append("\n");
            super.visit(hardLineBreak);
        }

        @Override
        public void visit(CustomNode customNode) {
            if (customNode instanceof TableRow) {
                super.visit(customNode);
                buffer.append("\n");
                return;
            }
            if (customNode instanceof TableCell) {
                super.visit(customNode);
                buffer.append(" | ");
                return;
            }
            super.visit(customNode);
        }

        void flush() {
            String content = buffer.toString().trim();
            if (hasHeading || !content.isEmpty()) {
                sections.add(new DocumentSection(currentHeading, content));
                buffer.setLength(0);
                hasHeading = false;
            }
        }

        private String extractNodeText(Node node) {
            StringBuilder sb = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override
                public void visit(Text t) {
                    sb.append(t.getLiteral());
                }

                @Override
                public void visit(Code c) {
                    sb.append(c.getLiteral());
                }
            });
            return sb.toString().trim();
        }
    }

    @RegisterForReflection
    public record DocumentSection(String heading, String content) {}

    @RegisterForReflection
    public record ParsedMarkdown(Map<String, List<String>> metadata, List<DocumentSection> sections) {
        public ParsedMarkdown {
            if (nonNull(metadata)) {
                metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            } else {
                metadata = Map.of();
            }
            if (nonNull(sections)) {
                sections = List.copyOf(sections);
            } else {
                sections = List.of();
            }
        }
    }
}
