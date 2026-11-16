package guru.thomasweber.devagent.workspace;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.isNull;

/**
 * Idempotently manages {@code .git/info/exclude} entries so sliced agent build directories,
 * caches, state sockets, and worker logs never pollute {@code git status}.
 */
@ApplicationScoped
public class GitExcludeManager {

    private static final String DEFAULT_TARGET_ENTRY = "target-agent/";
    private static final List<String> STANDARD_AGENT_EXCLUDES = List.of(".cache-agent/", "state.json", "worker.log");

    public void ensureExcluded(Path repoRoot, String targetDir) {
        if (isNull(repoRoot)) {
            return;
        }
        Optional<Path> excludeFileOpt = resolveExcludeFile(repoRoot);
        if (excludeFileOpt.isEmpty()) {
            return;
        }
        Path excludeFile = excludeFileOpt.get();
        List<String> requiredEntries = buildRequiredEntries(targetDir);

        try {
            Path infoDir = excludeFile.getParent();
            if (!Files.exists(infoDir, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(infoDir);
            }
            if (Files.isSymbolicLink(infoDir) || Files.isSymbolicLink(excludeFile)) {
                throw new SecurityException("Symbolic link detected in Git exclude path: " + excludeFile);
            }

            List<String> existingLines = Files.exists(excludeFile, LinkOption.NOFOLLOW_LINKS)
                ? Files.readAllLines(excludeFile)
                : List.of();
            Set<String> present = new LinkedHashSet<>(existingLines);
            List<String> updated = new ArrayList<>(existingLines);
            boolean changed = false;

            for (String entry : requiredEntries) {
                if (!present.contains(entry)) {
                    updated.add(entry);
                    present.add(entry);
                    changed = true;
                }
            }

            if (changed || !Files.exists(excludeFile, LinkOption.NOFOLLOW_LINKS)) {
                String content = String.join(System.lineSeparator(), updated) + System.lineSeparator();
                Files.writeString(
                    excludeFile,
                    content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                );
            }
        } catch (IOException e) {
            throw new WorkspaceException("Failed to update .git/info/exclude: " + excludeFile, e);
        }
    }

    private Optional<Path> resolveExcludeFile(Path repoRoot) {
        Path dotGit = repoRoot.resolve(".git");
        if (Files.isSymbolicLink(dotGit)) {
            throw new SecurityException("Symbolic link detected for .git path: " + dotGit);
        }
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(dotGit.resolve("info").resolve("exclude"));
        }
        if (Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return resolveWorktreeCommonExclude(dotGit);
        }
        return Optional.empty();
    }

    private Optional<Path> resolveWorktreeCommonExclude(Path dotGitFile) {
        try {
            String content = Files.readString(dotGitFile).trim();
            if (!content.startsWith("gitdir:")) {
                return Optional.empty();
            }
            Path gitDir = Path.of(content.substring("gitdir:".length()).trim());
            if (!gitDir.isAbsolute()) {
                gitDir = dotGitFile.getParent().resolve(gitDir).normalize();
            }
            Path commonDirFile = gitDir.resolve("commondir");
            Path commonGitDir = Files.isRegularFile(commonDirFile)
                ? gitDir.resolve(Files.readString(commonDirFile).trim()).normalize()
                : gitDir;
            return Optional.of(commonGitDir.resolve("info").resolve("exclude"));
        } catch (IOException e) {
            throw new WorkspaceException("Failed to resolve Git worktree exclude file", e);
        }
    }

    private List<String> buildRequiredEntries(String targetDir) {
        List<String> entries = new ArrayList<>();
        entries.add(normalizeDirectoryEntry(targetDir));
        entries.addAll(STANDARD_AGENT_EXCLUDES);
        return entries;
    }

    private String normalizeDirectoryEntry(String targetDir) {
        if (isNull(targetDir) || targetDir.isBlank()) {
            return DEFAULT_TARGET_ENTRY;
        }
        String trimmed = targetDir.trim().replace('\\', '/');
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
