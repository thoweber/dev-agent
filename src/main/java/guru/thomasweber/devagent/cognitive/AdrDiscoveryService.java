package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.segment.TextSegment;
import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.NioWorkspaceFileScanner;
import guru.thomasweber.devagent.workspace.RepoPath;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorkspaceFileScanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

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

    private final WorkspaceContextResolver contextResolver;
    private final DevAgentConfigLoader configLoader;
    private final WorkspaceFileScanner fileScanner;
    private final AdrParser adrParser;

    @Inject
    public AdrDiscoveryService(
        WorkspaceContextResolver contextResolver,
        DevAgentConfigLoader configLoader,
        WorkspaceFileScanner fileScanner,
        AdrParser adrParser
    ) {
        this.contextResolver = contextResolver;
        this.configLoader = configLoader;
        this.fileScanner = fileScanner;
        this.adrParser = adrParser;
    }

    public AdrDiscoveryService(
        WorkspaceContextResolver contextResolver,
        DevAgentConfigLoader configLoader,
        WorkspaceFileScanner fileScanner
    ) {
        this(contextResolver, configLoader, fileScanner, new AdrParser());
    }

    public AdrDiscoveryService(
        WorkspaceContextResolver contextResolver,
        DevAgentConfigLoader configLoader
    ) {
        this(contextResolver, configLoader, new NioWorkspaceFileScanner(), new AdrParser());
    }

    public AdrDiscoveryService(WorkspaceContextResolver contextResolver) {
        this(contextResolver, new DevAgentConfigLoader(), new NioWorkspaceFileScanner(), new AdrParser());
    }

    public List<ArchitecturalRecord> discoverRecords() {
        return discoverRecords(contextResolver.resolveRepoRoot());
    }

    public List<ArchitecturalRecord> discoverRecords(Path root) {
        if (root == null || !Files.exists(root)) {
            return Collections.emptyList();
        }

        List<String> configuredPaths = readConfiguredRulesPaths(root);
        List<String> pathsToScan = configuredPaths.isEmpty() ? DEFAULT_CONVENTION_PATHS : configuredPaths;

        RepoPath repoRoot = RepoPath.of(root, root);
        List<RepoPath> markdownFiles = fileScanner.findFiles(
            repoRoot,
            pathsToScan,
            WorkspaceFileScanner.DEFAULT_IGNORED_PREFIXES,
            p -> p.getFileName() != null && p.getFileName().toString().endsWith(".md")
        );

        return markdownFiles.stream()
            .map(adrParser::parseRecord)
            .toList();
    }

    public List<TextSegment> parseAdrDocument(Path filePath, String rawContent) {
        return adrParser.parseSegments(filePath, rawContent);
    }

    private List<String> readConfiguredRulesPaths(Path root) {
        Path agentsDir = root.resolve(".agents");
        Path configFile = agentsDir.resolve("dev-agent.yaml");
        if (Files.exists(agentsDir, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(agentsDir)) {
            throw new SecurityException("Forbidden symbolic link at .agents directory: " + agentsDir);
        }
        if (!Files.exists(configFile)) {
            return Collections.emptyList();
        }
        if (Files.isSymbolicLink(configFile)) {
            throw new SecurityException("Forbidden symbolic link at configuration file: " + configFile);
        }
        if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
            return Collections.emptyList();
        }
        RepoPath repoConfig = RepoPath.of(configFile, root);
        DevAgentConfig config = configLoader.load(repoConfig.toPath());
        return config.knowledge().rulesPaths().orElse(Collections.emptyList());
    }
}
