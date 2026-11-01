package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import guru.thomasweber.devagent.cognitive.MarkdownParserService.DocumentSection;
import guru.thomasweber.devagent.cognitive.MarkdownParserService.ParsedMarkdown;
import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.RepoPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated parser for Architectural Decision Records (ADRs) and declarative rules.
 * Extracts YAML frontmatter metadata, structured AST sections, and generates LangChain4j {@link TextSegment}s.
 */
@ApplicationScoped
public class AdrParser {

    private static final Pattern STATUS_PATTERN = Pattern.compile("(?i)(?:\\*\\s*\\*\\*status:\\*\\*|status:)\\s*([A-Za-z0-9_-]+)");
    private static final Pattern ADR_ID_HEADER_PATTERN = Pattern.compile("(?i)\\b([A-Z0-9]+-\\d+)\\b");
    private static final Pattern ADR_ID_FILE_PATTERN = Pattern.compile("(?i)^([A-Z0-9]+-\\d+)(?:-.*)?\\.md$");

    private final MarkdownParserService markdownParserService;

    @Inject
    public AdrParser(MarkdownParserService markdownParserService) {
        this.markdownParserService = markdownParserService;
    }

    public AdrParser() {
        this(new MarkdownParserService());
    }

    /**
     * Parses an architectural record directly from disk using a verified {@link RepoPath}.
     *
     * @param repoPath verified repository path
     * @return structured ArchitecturalRecord
     */
    public ArchitecturalRecord parseRecord(RepoPath repoPath) {
        try {
            String content = Files.readString(repoPath.toPath());
            return parseRecord(repoPath, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read record at " + repoPath.toPath(), e);
        }
    }

    /**
     * Parses an architectural record from raw markdown content and its associated {@link RepoPath}.
     *
     * @param repoPath verified repository path
     * @param content raw markdown document content
     * @return structured ArchitecturalRecord
     */
    public ArchitecturalRecord parseRecord(RepoPath repoPath, String content) {
        String fileName = repoPath.toPath().getFileName().toString();
        String nameWithoutExt = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;

        ParsedMarkdown parsed = markdownParserService.parse(content);

        // 1. Title
        String title = deriveTitle(parsed, nameWithoutExt);

        // 2. ID
        String id = deriveId(parsed, fileName, title, nameWithoutExt);

        // 3. Status
        String status = getFirstMetadata(parsed.metadata(), "status");
        if (status == null) {
            Matcher statusMatcher = STATUS_PATTERN.matcher(content);
            status = statusMatcher.find() ? statusMatcher.group(1).trim() : "Active";
        }

        // 4. Summary
        String summary = getFirstMetadata(parsed.metadata(), "summary");
        if (summary == null) {
            summary = parsed.sections().stream()
                .filter(s -> "Summary".equalsIgnoreCase(s.heading()) || "Context".equalsIgnoreCase(s.heading()))
                .map(DocumentSection::content)
                .findFirst()
                .orElse(null);
        }
        if (summary == null || summary.isBlank()) {
            summary = parsed.sections().stream()
                .map(DocumentSection::content)
                .flatMap(c -> c.lines())
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.toLowerCase().startsWith("status:"))
                .findFirst()
                .orElse(title);
        }

        // 5. Tags
        List<String> tags = new ArrayList<>();
        if (parsed.metadata().containsKey("tags")) {
            tags.addAll(parsed.metadata().get("tags"));
        }
        tags.addAll(deriveTags(repoPath));
        tags = tags.stream().distinct().toList();

        return ArchitecturalRecord.builder()
            .id(id)
            .title(title)
            .status(status)
            .tags(tags)
            .path(repoPath)
            .summary(summary)
            .content(content)
            .build();
    }

    /**
     * Splits an ADR or rule document into searchable LangChain4j {@link TextSegment} chunks.
     *
     * @param filePath path to the file (optional)
     * @param rawContent raw markdown content
     * @return list of segmented text chunks with metadata
     */
    public List<TextSegment> parseSegments(Path filePath, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return Collections.emptyList();
        }

        ParsedMarkdown parsed = markdownParserService.parse(rawContent);

        String fileName = (filePath != null && filePath.getFileName() != null) ? filePath.getFileName().toString() : "unknown.md";
        String nameWithoutExt = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;

        String title = deriveTitle(parsed, nameWithoutExt);
        String docId = deriveId(parsed, fileName, title, nameWithoutExt);

        List<TextSegment> segments = new ArrayList<>();
        for (DocumentSection section : parsed.sections()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("path", filePath != null ? filePath.toString() : "");
            metadata.put("id", docId);
            metadata.put("title", title);
            metadata.put("section", section.heading());

            String segmentBody = String.format("[%s - %s] %s\n%s", docId, title, section.heading(), section.content());
            segments.add(TextSegment.from(segmentBody, new Metadata(metadata)));
        }
        return segments;
    }

    private String deriveTitle(ParsedMarkdown parsed, String fallback) {
        String title = getFirstMetadata(parsed.metadata(), "title");
        if (title == null) {
            title = parsed.sections().stream()
                .map(DocumentSection::heading)
                .filter(h -> !"Preamble".equalsIgnoreCase(h))
                .findFirst()
                .orElse(fallback);
        }
        return title;
    }

    private String deriveId(ParsedMarkdown parsed, String fileName, String title, String nameWithoutExt) {
        String id = getFirstMetadata(parsed.metadata(), "id");
        if (id == null) {
            Matcher fileIdMatcher = ADR_ID_FILE_PATTERN.matcher(fileName);
            if (fileIdMatcher.find()) {
                id = fileIdMatcher.group(1);
            } else {
                Matcher headerIdMatcher = ADR_ID_HEADER_PATTERN.matcher(title);
                if (headerIdMatcher.find()) {
                    id = headerIdMatcher.group(1);
                } else {
                    id = nameWithoutExt;
                }
            }
        }
        return id;
    }

    private static String getFirstMetadata(Map<String, List<String>> metadata, String key) {
        List<String> values = metadata.get(key);
        if (values != null && !values.isEmpty()) {
            String first = values.getFirst();
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
        }
        return null;
    }

    private List<String> deriveTags(RepoPath repoPath) {
        List<String> tags = new ArrayList<>();
        String rel = repoPath.relativize().toString().toLowerCase().replace('\\', '/');
        if (rel.matches(".*(?i)(?:^|/)(?:adr|adrs)(?:/|[-_.]|$).*")) {
            tags.add("adr");
        }
        if (rel.matches(".*(?i)(?:^|/)(?:rule|rules)(?:/|[-_.]|$).*")) {
            tags.add("rule");
        }
        return Collections.unmodifiableList(tags);
    }
}
