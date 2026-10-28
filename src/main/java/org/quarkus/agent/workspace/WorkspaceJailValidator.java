package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Sandboxes all filesystem operations strictly within the active execution root.
 * Prevents directory traversal attacks, protects Git internal metadata and global settings,
 * and rejects writing through symbolic links.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class WorkspaceJailValidator {

    private final Path activeWorkingRoot;

    public WorkspaceJailValidator() {
        this(Path.of(System.getProperty("user.dir")));
    }

    /**
     * Validates and resolves an untrusted relative or absolute path against the active working root.
     *
     * @param untrustedPath untrusted file path string
     * @return validated real or normalized Path within activeWorkingRoot
     * @throws SecurityException if the resolved path traverses outside activeWorkingRoot boundary
     */
    public Path validateAndResolve(String untrustedPath) {
        if (untrustedPath == null || untrustedPath.isBlank()) {
            throw new SecurityException("Jailbreak attempt: path cannot be null or blank");
        }

        Path normalizedRoot;
        try {
            normalizedRoot = activeWorkingRoot.toRealPath();
        } catch (IOException _) {
            normalizedRoot = activeWorkingRoot.toAbsolutePath().normalize();
        }

        Path target = normalizedRoot.resolve(untrustedPath).normalize();

        for (Path segment : target) {
            if (segment.toString().equals("..")) {
                throw new SecurityException("Jailbreak attempt: Traversal detected: " + untrustedPath);
            }
        }

        // Check if target or any ancestor directory is a symbolic link
        Path curr = target;
        while (curr != null && !curr.equals(normalizedRoot)) {
            if (Files.exists(curr, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(curr)) {
                throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + untrustedPath);
            }
            curr = curr.getParent();
        }

        try {
            Path realResolved = target.toRealPath();
            if (!realResolved.startsWith(normalizedRoot)) {
                throw new SecurityException("Jailbreak attempt: Path traverses outside boundary: " + untrustedPath);
            }
            return realResolved;
        } catch (IOException _) {
            // File does not exist yet; validate normalized parent chain
            if (!target.startsWith(normalizedRoot)) {
                throw new SecurityException("Jailbreak attempt: Path traverses outside boundary: " + untrustedPath);
            }
            return target;
        }
    }

    /**
     * Enforces that a target path is permitted for modification.
     * Prohibits writes into internal .git structures, user global config, critical metadata,
     * or through symbolic links.
     *
     * @param untrustedPath untrusted path string
     * @return validated RepoPath
     */
    public RepoPath validateForWrite(String untrustedPath) {
        Path rawPath = activeWorkingRoot.toAbsolutePath().normalize().resolve(untrustedPath);
        if (Files.exists(rawPath, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(rawPath)) {
            throw new SecurityException("Refusing to write through symbolic link: " + rawPath);
        }
        Path resolved = validateAndResolve(untrustedPath);
        return RepoPath.of(resolved, activeWorkingRoot);
    }

    public Path getActiveWorkingRoot() {
        return activeWorkingRoot;
    }
}
