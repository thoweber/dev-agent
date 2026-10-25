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

    private static final Pattern SCP_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+:(.+)$");
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://[^/]+/(.+)$");

    @Inject
    WorkspaceContextResolver workspaceResolver;

    public String resolveProjectId() {
        return resolveForPath(workspaceResolver.getProjectRoot());
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
            clean = scpMatcher.group(1);
        } else {
            Matcher urlMatcher = URL_PATTERN.matcher(clean);
            if (urlMatcher.matches()) {
                clean = urlMatcher.group(1);
            }
        }
        if (clean.endsWith(".git")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        return clean;
    }

    private String probeGitRemote(Path rootPath) {
        try {
            Process process = new ProcessBuilder("git", "config", "--get", "remote.origin.url")
                .directory(rootPath.toFile())
                .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            // Git command failed or directory not a git repo
        }
        return null;
    }

    private String computeSha256Fallback(Path rootPath) {
        try {
            Path real = rootPath.toRealPath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(real.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "default-project";
        }
    }
}
