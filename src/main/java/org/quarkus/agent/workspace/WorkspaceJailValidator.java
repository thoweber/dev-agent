package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Sandboxes all filesystem operations strictly within the active execution root.
 * Prevents directory traversal attacks and protects Git internal metadata and global settings.
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
        Objects.requireNonNull(untrustedPath, "untrustedPath must not be null");

        Path normalizedRoot;
        try {
            normalizedRoot = activeWorkingRoot.toRealPath();
        } catch (IOException e) {
            normalizedRoot = activeWorkingRoot.toAbsolutePath().normalize();
        }

        try {
            Path target = normalizedRoot.resolve(untrustedPath);
            Path realResolved = target.toRealPath();
            if (!realResolved.startsWith(normalizedRoot)) {
                throw new SecurityException("Jailbreak attempt: Path traverses outside boundary: " + untrustedPath);
            }
            return realResolved;
        } catch (IOException e) {
            // File does not exist yet; validate normalized parent chain
            Path resolved = normalizedRoot.resolve(untrustedPath).normalize();
            if (!resolved.startsWith(normalizedRoot)) {
                throw new SecurityException("Jailbreak attempt: Path traverses outside boundary: " + untrustedPath);
            }
            return resolved;
        }
    }

    /**
     * Enforces that a target path is permitted for modification.
     * Prohibits writes into internal .git structures, user global config, or critical metadata.
     *
     * @param untrustedPath untrusted path string
     * @return validated Path
     */
    public Path validateForWrite(String untrustedPath) {
        Path resolved = validateAndResolve(untrustedPath);
        Path normalizedRoot = activeWorkingRoot.toAbsolutePath().normalize();
        Path relative = normalizedRoot.relativize(resolved.toAbsolutePath().normalize());

        String pathStr = relative.toString().replace('\\', '/');
        if (pathStr.equals(".git") || pathStr.startsWith(".git/")) {
            throw new SecurityException("Protected location: modification of .git directory is forbidden: " + untrustedPath);
        }

        return resolved;
    }

    public Path getActiveWorkingRoot() {
        return activeWorkingRoot;
    }
}
