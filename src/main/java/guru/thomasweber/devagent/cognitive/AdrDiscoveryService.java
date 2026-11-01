package guru.thomasweber.devagent.cognitive;

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

    private static final Pattern TITLE_PATTERN = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern STATUS_PATTERN = Pattern.compile("(?i)(?:\\*\\s*\\*\\*status:\\*\\*|status:)\\s*([A-Za-z0-9_-]+)");
    private static final Pattern ADR_ID_HEADER_PATTERN = Pattern.compile("(?i)\\b([A-Z0-9]+-\\d+)\\b");
    private static final Pattern ADR_ID_FILE_PATTERN = Pattern.compile("(?i)^([A-Z0-9]+-\\d+)(?:-.*)?\\.md$");
    private static final Pattern SUMMARY_SECTION_PATTERN = Pattern.compile("(?is)##\\s*(?:summary|context)\\s*\\n+([^#\\n][^#]*?)(?=\\n##|\\Z)");

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceJailValidator jailValidator;

    @Inject
    public AdrDiscoveryService(WorkspaceContextResolver contextResolver, WorkspaceJailValidator jailValidator) {
        this.contextResolver = contextResolver;
        this.jailValidator = jailValidator;
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
        try {
            List<String> lines = Files.readAllLines(configFile);
            List<String> paths = new ArrayList<>();
            boolean inKnowledge = false;
            boolean inRulesPaths = false;
            int rulesPathsIndent = -1;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    continue;
                }
                int indent = getIndent(line);
                if (trimmed.startsWith("knowledge:")) {
                    inKnowledge = true;
                    inRulesPaths = false;
                    continue;
                }
                if (inKnowledge && trimmed.startsWith("rules-paths:")) {
                    inRulesPaths = true;
                    rulesPathsIndent = indent;
                    continue;
                }
                if (inRulesPaths) {
                    if (indent <= rulesPathsIndent && !trimmed.startsWith("-")) {
                        inRulesPaths = false;
                        if (indent == 0) {
                            inKnowledge = false;
                        }
                    } else if (trimmed.startsWith("-")) {
                        String item = stripQuotes(trimmed.substring(1).trim());
                        paths.add(item);
                    }
                }
            }
            return paths;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config file: " + configFile, e);
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    private int getIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
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

    private ArchitecturalRecord parseRecord(RepoPath repoPath) {
        try {
            String content = Files.readString(repoPath.toPath());
            String fileName = repoPath.toPath().getFileName().toString();
            String nameWithoutExt = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;

            // Title
            String title = nameWithoutExt;
            Matcher titleMatcher = TITLE_PATTERN.matcher(content);
            if (titleMatcher.find()) {
                title = titleMatcher.group(1).trim();
            }

            // ID
            String id = nameWithoutExt;
            Matcher fileIdMatcher = ADR_ID_FILE_PATTERN.matcher(fileName);
            if (fileIdMatcher.find()) {
                id = fileIdMatcher.group(1);
            } else {
                Matcher headerIdMatcher = ADR_ID_HEADER_PATTERN.matcher(title);
                if (headerIdMatcher.find()) {
                    id = headerIdMatcher.group(1);
                }
            }

            // Status
            String status = "Active";
            Matcher statusMatcher = STATUS_PATTERN.matcher(content);
            if (statusMatcher.find()) {
                status = statusMatcher.group(1).trim();
            }

            // Summary
            String summary = title;
            Matcher summaryMatcher = SUMMARY_SECTION_PATTERN.matcher(content);
            if (summaryMatcher.find()) {
                summary = summaryMatcher.group(1).trim();
            } else {
                String[] paragraphs = content.split("\\n\\s*\\n");
                for (String p : paragraphs) {
                    String clean = p.replaceAll("^[#*\\s-]+", "").trim();
                    if (!clean.isEmpty() && !clean.toLowerCase().startsWith("status:")) {
                        summary = clean;
                        break;
                    }
                }
            }

            List<String> tags = deriveTags(repoPath);

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
