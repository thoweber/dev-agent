package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a persistent, isolated project coordinate from Git remote configuration or workspace path.
 */
@ApplicationScoped
public class ProjectIdResolver {

    private static final Pattern SCP_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+@([a-zA-Z0-9._-]+):(.+)$");
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://([^/]+)/(.+)$");

    private final WorkspaceContextResolver workspaceResolver;

    @Inject
    public ProjectIdResolver(WorkspaceContextResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    public ProjectIdResolver() {
        this(new WorkspaceContextResolver());
    }

    public String resolveProjectId() {
        return resolveForPath(workspaceResolver.resolveRepoRoot());
    }

    public String resolveForPath(Path rootPath) {
        String remote = probeGitRemote(rootPath);
        if (remote != null && !remote.isBlank()) {
            return normalizeRemote(remote);
        }
        return computeSha256Fallback(rootPath);
    }

    public String normalizeRemote(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return "unknown";
        }
        String clean = remoteUrl.trim();
        Matcher scpMatcher = SCP_PATTERN.matcher(clean);
        if (scpMatcher.matches()) {
            clean = scpMatcher.group(1) + "/" + scpMatcher.group(2);
        } else {
            Matcher urlMatcher = URL_PATTERN.matcher(clean);
            if (urlMatcher.matches()) {
                clean = urlMatcher.group(1) + "/" + urlMatcher.group(2);
            }
        }
        if (clean.endsWith(".git")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        return clean;
    }

    @SuppressWarnings("java:S4036")
    private String probeGitRemote(Path rootPath) {
        try {
            Process process = new ProcessBuilder("git", "config", "--get", "remote.origin.url")
                .directory(rootPath.toFile())
                .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            } else if (process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (IOException _) {
            // Git command failed or directory not a git repo
        }
        return null;
    }

    private String computeSha256Fallback(Path rootPath) {
        Path targetPath;
        try {
            targetPath = rootPath.toRealPath();
        } catch (IOException _) {
            targetPath = rootPath.toAbsolutePath().normalize();
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(targetPath.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm must be supported by the JVM runtime", e);
        }
    }
}
