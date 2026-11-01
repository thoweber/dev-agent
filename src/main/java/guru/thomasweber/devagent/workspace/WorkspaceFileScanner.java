package guru.thomasweber.devagent.workspace;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Service port for safely scanning and discovering files within a workspace repository.
 * Consumes and produces strictly strongly-typed {@link RepoPath} instances to preserve path jail invariants.
 */
public interface WorkspaceFileScanner {

    Set<String> DEFAULT_IGNORED_PREFIXES = Set.of(
        ".git",
        "target-agent",
        "target",
        "node_modules"
    );

    List<RepoPath> findFiles(RepoPath root, String pattern);

    List<RepoPath> findFiles(RepoPath root, String pattern, Set<String> ignorePrefixes);

    List<RepoPath> findFiles(RepoPath root, Collection<String> patterns, Set<String> ignorePrefixes);

    List<RepoPath> findFiles(
        RepoPath root,
        Collection<String> patterns,
        Set<String> ignorePrefixes,
        Predicate<Path> fileFilter
    );
}
