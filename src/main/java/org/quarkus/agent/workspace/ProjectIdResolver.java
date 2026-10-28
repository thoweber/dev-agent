package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkus.agent.model.ProjectId;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a persistent, isolated project coordinate from Git remote configuration or workspace path.
 */
@ApplicationScoped
public class ProjectIdResolver {

    private static final Pattern SCP_PATTERN = Pattern.compile("^(?:[\\w.-]+@)?([\\w.-]+):(.+)$");
    private static final Pattern URL_PATTERN = Pattern.compile("^(?:[a-zA-Z][a-zA-Z0-9+-.]*://)?(?:[^@/]+@)?([^:/]+)(?::\\d+)?/(.+)$");

    private final WorkspaceContextResolver workspaceResolver;
    private final SubprocessExecutor subprocessExecutor;

    @Inject
    public ProjectIdResolver(WorkspaceContextResolver workspaceResolver, SubprocessExecutor subprocessExecutor) {
        this.workspaceResolver = workspaceResolver;
        this.subprocessExecutor = subprocessExecutor;
    }

    public ProjectIdResolver(WorkspaceContextResolver workspaceResolver) {
        this(workspaceResolver, new SubprocessExecutor());
    }

    public ProjectIdResolver() {
        this(new WorkspaceContextResolver(), new SubprocessExecutor());
    }

    public ProjectId resolveProjectId() {
        return resolveForPath(workspaceResolver.resolveRepoRoot());
    }

    public ProjectId resolveForPath(Path rootPath) {
        String coordinate = resolveCoordinate(rootPath);
        return ProjectId.fromCoordinate(coordinate);
    }

    public String resolveCoordinate() {
        return resolveCoordinate(workspaceResolver.resolveRepoRoot());
    }

    public String resolveCoordinate(Path rootPath) {
        String remote = probeGitRemote(rootPath);
        if (remote != null && !remote.isBlank()) {
            return normalizeRemote(remote);
        }
        return computeLocalFallback(rootPath);
    }

    public String normalizeRemote(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return "unknown";
        }
        String clean = remoteUrl.trim();
        HostAndPath parsed = parseHostAndPath(clean);
        if (parsed != null) {
            String host = parsed.host().toLowerCase(Locale.ROOT);
            String path = cleanPath(parsed.path());
            return host + "/" + path;
        }

        return cleanPath(clean);
    }

    private record HostAndPath(String host, String path) {}

    private HostAndPath parseHostAndPath(String clean) {
        if (!clean.contains("://")) {
            Matcher scpMatcher = SCP_PATTERN.matcher(clean);
            if (scpMatcher.matches()) {
                return new HostAndPath(scpMatcher.group(1), scpMatcher.group(2));
            }
        }
        Matcher urlMatcher = URL_PATTERN.matcher(clean);
        if (urlMatcher.matches()) {
            return new HostAndPath(urlMatcher.group(1), urlMatcher.group(2));
        }
        return null;
    }

    private String cleanPath(String rawPath) {
        String p = rawPath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.endsWith(".git")) {
            p = p.substring(0, p.length() - 4);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String probeGitRemote(Path rootPath) {
        ProcessResult result = subprocessExecutor.execute(
            List.of("git", "config", "--get", "remote.origin.url"),
            rootPath,
            Duration.ofSeconds(2)
        );
        if (result.isSuccess()) {
            String remote = result.stdOut().trim();
            return remote.isBlank() ? null : remote;
        }
        return null;
    }

    private String computeLocalFallback(Path rootPath) {
        Path targetPath;
        try {
            targetPath = rootPath.toRealPath();
        } catch (IOException _) {
            targetPath = rootPath.toAbsolutePath().normalize();
        }
        return "local:" + targetPath;
    }
}
