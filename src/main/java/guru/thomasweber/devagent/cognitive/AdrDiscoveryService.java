package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import guru.thomasweber.devagent.cognitive.MarkdownParserService.DocumentSection;
import guru.thomasweber.devagent.cognitive.MarkdownParserService.ParsedMarkdown;
import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.RepoPath;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorkspaceJailValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dynamically discovers architectural decision records (ADRs) and declarative rules
 * from user-configured YAML paths or standard project convention roots.
 */
@ApplicationScoped
public class AdrDiscoveryService {

    private static final List<String> DEFAULT_CONVENTION_PATHS = List.of(
        "docs/adr",
        "doc/architecture/decisions",
        "docs/tech/architecture/adrs.md",
        ".agents/rules",
        "ADR.md"
    );

    private static final Pattern STATUS_PATTERN = Pattern.compile("(?i)(?:\\*\\s*\\*\\*status:\\*\\*|status:)\\s*([A-Za-z0-9_-]+)");
    private static final Pattern ADR_ID_HEADER_PATTERN = Pattern.compile("(?i)\\b([A-Z0-9]+-\\d+)\\b");
    private static final Pattern ADR_ID_FILE_PATTERN = Pattern.compile("(?i)^([A-Z0-9]+-\\d+)(?:-.*)?\\.md$");

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceJailValidator jailValidator;
    private final MarkdownParserService markdownParserService;
    private final DevAgentConfigLoader configLoader;

    @Inject
    public AdrDiscoveryService(
        WorkspaceContextResolver contextResolver,
        WorkspaceJailValidator jailValidator,
        MarkdownParserService markdownParserService,
        DevAgentConfigLoader configLoader
    ) {
        this.contextResolver = contextResolver;
        this.jailValidator = jailValidator;
        this.markdownParserService = markdownParserService;
        this.configLoader = configLoader;
    }

    public AdrDiscoveryService(
        WorkspaceContextResolver contextResolver,
        WorkspaceJailValidator jailValidator,
        MarkdownParserService markdownParserService
    ) {
        this(contextResolver, jailValidator, markdownParserService, new DevAgentConfigLoader());
    }

    public AdrDiscoveryService(WorkspaceContextResolver contextResolver, WorkspaceJailValidator jailValidator) {
        this(contextResolver, jailValidator, new MarkdownParserService(), new DevAgentConfigLoader());
    }

    public List<ArchitecturalRecord> discoverRecords() {
        return discoverRecords(contextResolver.resolveRepoRoot());
    }

    public List<ArchitecturalRecord> discoverRecords(Path root) {
        WorkspaceJailValidator effectiveJail = (root.equals(jailValidator.getActiveWorkingRoot()))
            ? jailValidator
            : new WorkspaceJailValidator(root);

        List<String> configuredPaths = readConfiguredRulesPaths(root);
        List<String> pathsToScan = configuredPaths.isEmpty() ? DEFAULT_CONVENTION_PATHS : configuredPaths;

        Map<Path, RepoPath> uniqueFiles = new LinkedHashMap<>();

        for (String pattern : pathsToScan) {
            validateNoTraversal(pattern);
            scanPattern(pattern, root, effectiveJail, uniqueFiles);
        }

        List<ArchitecturalRecord> records = new ArrayList<>();
        for (RepoPath repoPath : uniqueFiles.values()) {
            ArchitecturalRecord record = parseRecord(repoPath);
            if (record != null) {
                records.add(record);
            }
        }

        return records;
    }

    private List<String> readConfiguredRulesPaths(Path root) {
        Path configFile = root.resolve(".agents").resolve("dev-agent.yaml");
        if (!Files.isRegularFile(configFile)) {
            return Collections.emptyList();
        }
        DevAgentConfig config = configLoader.load(configFile);
        return config.knowledge().rulesPaths().orElse(Collections.emptyList());
    }

    private void validateNoTraversal(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new SecurityException("Jailbreak attempt: pattern cannot be null or blank");
        }
        for (String segment : pattern.replace('\\', '/').split("/")) {
            if (segment.equals("..")) {
                throw new SecurityException("Jailbreak attempt: Traversal detected: " + pattern);
            }
        }
    }

    private void scanPattern(String pattern, Path root, WorkspaceJailValidator jail, Map<Path, RepoPath> uniqueFiles) {
        String normalizedPattern = pattern.replace('\\', '/');
        if (normalizedPattern.startsWith("./")) {
            normalizedPattern = normalizedPattern.substring(2);
        }

        if (normalizedPattern.contains("*")) {
            int firstStar = normalizedPattern.indexOf('*');
            int lastSlash = normalizedPattern.lastIndexOf('/', firstStar);
            String prefix = (lastSlash >= 0) ? normalizedPattern.substring(0, lastSlash) : "";
            Path searchRoot = prefix.isEmpty() ? root : root.resolve(prefix);
            if (!Files.exists(searchRoot)) {
                return;
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);
            try (Stream<Path> stream = Files.walk(searchRoot)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !isIgnored(root, p))
                    .forEach(file -> {
                        Path rel = root.relativize(file);
                        String relStr = rel.toString().replace('\\', '/');
                        if (matcher.matches(Path.of(relStr))) {
                            Path resolved = jail.validateAndResolve(relStr);
                            RepoPath repoPath = RepoPath.of(resolved, root);
                            uniqueFiles.putIfAbsent(repoPath.toPath(), repoPath);
                        }
                    });
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk workspace root: " + searchRoot, e);
            }
        } else {
            Path path = root.resolve(normalizedPattern);
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !isIgnored(root, p))
                        .forEach(file -> {
                            Path rel = root.relativize(file);
                            String relStr = rel.toString().replace('\\', '/');
                            Path resolved = jail.validateAndResolve(relStr);
                            RepoPath repoPath = RepoPath.of(resolved, root);
                            uniqueFiles.putIfAbsent(repoPath.toPath(), repoPath);
                        });
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to scan directory: " + path, e);
                }
            } else if (Files.isRegularFile(path) && normalizedPattern.endsWith(".md")) {
                Path rel = root.relativize(path);
                String relStr = rel.toString().replace('\\', '/');
                Path resolved = jail.validateAndResolve(relStr);
                RepoPath repoPath = RepoPath.of(resolved, root);
                uniqueFiles.putIfAbsent(repoPath.toPath(), repoPath);
            }
        }
    }

    private boolean isIgnored(Path root, Path file) {
        Path rel = root.relativize(file);
        String relStr = rel.toString().replace('\\', '/');
        return relStr.startsWith(".git/") || relStr.startsWith("target-agent/") || relStr.startsWith("target/") || relStr.contains("/node_modules/");
    }

    public List<TextSegment> parseAdrDocument(Path filePath, String rawContent) {
        ParsedMarkdown parsed = markdownParserService.parse(rawContent);

        String fileName = (filePath != null && filePath.getFileName() != null) ? filePath.getFileName().toString() : "unknown.md";
        String docId = getFirstMetadata(parsed.metadata(), "id");
        if (docId == null) {
            docId = fileName;
        }
        String title = getFirstMetadata(parsed.metadata(), "title");
        if (title == null) {
            title = parsed.sections().stream()
                .map(DocumentSection::heading)
                .filter(h -> !"Preamble".equalsIgnoreCase(h))
                .findFirst()
                .orElse("");
        }

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

    private ArchitecturalRecord parseRecord(RepoPath repoPath) {
        try {
            String content = Files.readString(repoPath.toPath());
            String fileName = repoPath.toPath().getFileName().toString();
            String nameWithoutExt = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;

            ParsedMarkdown parsed = markdownParserService.parse(content);

            // Title
            String title = getFirstMetadata(parsed.metadata(), "title");
            if (title == null) {
                title = parsed.sections().stream()
                    .map(DocumentSection::heading)
                    .filter(h -> !"Preamble".equalsIgnoreCase(h))
                    .findFirst()
                    .orElse(nameWithoutExt);
            }

            // ID
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

            // Status
            String status = getFirstMetadata(parsed.metadata(), "status");
            if (status == null) {
                Matcher statusMatcher = STATUS_PATTERN.matcher(content);
                status = statusMatcher.find() ? statusMatcher.group(1).trim() : "Active";
            }

            // Summary
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
                    .filter(c -> !c.isBlank() && !c.toLowerCase().startsWith("status:"))
                    .findFirst()
                    .map(c -> {
                        int nl = c.indexOf('\n');
                        return nl > 0 ? c.substring(0, nl).trim() : c.trim();
                    })
                    .orElse(title);
            }

            // Tags
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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read record at " + repoPath.toPath(), e);
        }
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
