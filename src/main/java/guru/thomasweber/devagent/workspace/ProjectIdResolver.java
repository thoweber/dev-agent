package guru.thomasweber.devagent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import guru.thomasweber.devagent.model.ProjectId;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Resolves a persistent, isolated project coordinate from Git remote configuration or workspace path.
 */
@ApplicationScoped
public class ProjectIdResolver {

    private static final Pattern SCP_PATTERN = Pattern.compile("^(?:[\\w.-]+@)?([\\w.-]+):(.+)$");

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
        if (isNull(rootPath)) {
            return computeLocalFallback(null);
        }
        String remote = probeGitRemote(rootPath);
        if (nonNull(remote) && !remote.isBlank()) {
            return normalizeRemote(remote);
        }
        return computeLocalFallback(rootPath);
    }

    public String normalizeRemote(String remoteUrl) {
        if (isNull(remoteUrl) || remoteUrl.isBlank()) {
            return "unknown";
        }
        String clean = remoteUrl.trim();
        HostAndPath parsed = parseHostAndPath(clean);
        if (nonNull(parsed)) {
            String host = parsed.host().toLowerCase(Locale.ROOT);
            String path = cleanPath(parsed.path());
            return host + "/" + path;
        }

        return cleanPath(clean);
    }

    private record HostAndPath(String host, String path) {}

    private HostAndPath parseHostAndPath(String clean) {
        if (isWindowsDrivePath(clean)) {
            return null;
        }
        int schemeIdx = clean.indexOf("://");
        if (schemeIdx < 0) {
            Matcher scpMatcher = SCP_PATTERN.matcher(clean);
            if (scpMatcher.matches()) {
                return new HostAndPath(scpMatcher.group(1), scpMatcher.group(2));
            }
            return parseUrlAuthorityAndPath(clean);
        }
        String scheme = clean.substring(0, schemeIdx);
        if (!isValidScheme(scheme)) {
            return null;
        }
        return parseUrlAuthorityAndPath(clean.substring(schemeIdx + 3));
    }

    private static boolean isValidScheme(String scheme) {
        if (scheme.isEmpty() || !Character.isLetter(scheme.charAt(0))) {
            return false;
        }
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static HostAndPath parseUrlAuthorityAndPath(String remainder) {
        int slashIdx = remainder.indexOf('/');
        if (slashIdx <= 0 || slashIdx == remainder.length() - 1) {
            return null;
        }
        String authority = remainder.substring(0, slashIdx);
        String path = remainder.substring(slashIdx + 1);
        int atIdx = authority.lastIndexOf('@');
        String hostPort = authority;
        if (atIdx >= 0) {
            hostPort = authority.substring(atIdx + 1);
        }
        String host = extractHost(hostPort);
        if (isNull(host)) {
            return null;
        }
        return new HostAndPath(host, path);
    }

    private static String extractHost(String hostPort) {
        if (hostPort.isEmpty()) {
            return null;
        }
        if (hostPort.startsWith("[")) {
            int closeBracket = hostPort.indexOf(']');
            if (closeBracket <= 1) {
                return null;
            }
            String ipv6 = hostPort.substring(0, closeBracket + 1);
            String after = hostPort.substring(closeBracket + 1);
            if (after.isEmpty() || isValidPortSuffix(after)) {
                return ipv6;
            }
            return null;
        }
        int colonIdx = hostPort.indexOf(':');
        if (colonIdx < 0) {
            return hostPort;
        }
        if (colonIdx == 0 || !isValidPortSuffix(hostPort.substring(colonIdx))) {
            return null;
        }
        return hostPort.substring(0, colonIdx);
    }

    private static boolean isValidPortSuffix(String suffix) {
        if (suffix.length() < 2 || suffix.charAt(0) != ':') {
            return false;
        }
        for (int i = 1; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isWindowsDrivePath(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private String cleanPath(String rawPath) {
        String p = rawPath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.toLowerCase(Locale.ROOT).endsWith(".git")) {
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
            if (remote.isBlank()) {
                return null;
            }
            return remote;
        }
        return null;
    }

    private String computeLocalFallback(Path rootPath) {
        if (isNull(rootPath)) {
            return "unknown";
        }
        Path targetPath;
        try {
            targetPath = rootPath.toRealPath();
        } catch (IOException _) {
            targetPath = rootPath.toAbsolutePath().normalize();
        }
        return "local:" + targetPath;
    }
}
