package guru.thomasweber.devagent.workspace;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Standard-library-backed file scanner implementing {@link WorkspaceFileScanner}.
 * Uses pure Java NIO {@link Files#walkFileTree} and {@link PathMatcher} while enforcing
 * path jail containment and returning strictly verified {@link RepoPath} domain value objects.
 */
@ApplicationScoped
public class NioWorkspaceFileScanner implements WorkspaceFileScanner {

    @Override
    public List<RepoPath> findFiles(RepoPath root, String pattern) {
        return findFiles(root, pattern, DEFAULT_IGNORED_PREFIXES);
    }

    @Override
    public List<RepoPath> findFiles(RepoPath root, String pattern, Set<String> ignorePrefixes) {
        return findFiles(root, Collections.singletonList(pattern), ignorePrefixes, null);
    }

    @Override
    public List<RepoPath> findFiles(RepoPath root, Collection<String> patterns, Set<String> ignorePrefixes) {
        return findFiles(root, patterns, ignorePrefixes, null);
    }

    @Override
    public List<RepoPath> findFiles(
        RepoPath root,
        Collection<String> patterns,
        Set<String> ignorePrefixes,
        Predicate<Path> fileFilter
    ) {
        Objects.requireNonNull(root, "root must not be null");
        if (patterns == null || patterns.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> effectiveIgnores = (ignorePrefixes != null) ? ignorePrefixes : DEFAULT_IGNORED_PREFIXES;
        Path rootPath = root.toPath();
        Map<Path, RepoPath> uniqueFiles = new LinkedHashMap<>();

        for (String pattern : patterns) {
            validatePattern(pattern);
            scanSinglePattern(rootPath, pattern, effectiveIgnores, fileFilter, uniqueFiles);
        }

        return new ArrayList<>(uniqueFiles.values());
    }

    private void validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new SecurityException("Jailbreak attempt: pattern cannot be null or blank");
        }
        String normalized = pattern.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[a-zA-Z]:/.*")) {
            throw new SecurityException("Jailbreak attempt: absolute pattern not allowed: " + pattern);
        }
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (isWindows && normalized.matches("^[a-zA-Z]:.*")) {
            throw new SecurityException("Jailbreak attempt: drive-relative pattern not allowed on Windows: " + pattern);
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new SecurityException("Jailbreak attempt: Traversal detected: " + pattern);
            }
        }
    }

    private void scanSinglePattern(
        Path rootPath,
        String pattern,
        Set<String> ignorePrefixes,
        Predicate<Path> fileFilter,
        Map<Path, RepoPath> uniqueFiles
    ) {
        String normalized = pattern.replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }

        if (normalized.contains("*") || normalized.contains("?")) {
            scanWildcardPattern(rootPath, normalized, ignorePrefixes, fileFilter, uniqueFiles);
        } else {
            scanExactPattern(rootPath, normalized, ignorePrefixes, fileFilter, uniqueFiles);
        }
    }

    private void scanWildcardPattern(
        Path rootPath,
        String normalized,
        Set<String> ignorePrefixes,
        Predicate<Path> fileFilter,
        Map<Path, RepoPath> uniqueFiles
    ) {
        int firstWildcard = findFirstWildcard(normalized);
        int lastSlashBeforeWildcard = normalized.lastIndexOf('/', firstWildcard);
        String prefix = (lastSlashBeforeWildcard >= 0) ? normalized.substring(0, lastSlashBeforeWildcard) : "";
        Path searchRoot = prefix.isEmpty() ? rootPath : rootPath.resolve(prefix);
        if (!Files.exists(searchRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        RepoPath.of(searchRoot, rootPath);
        List<String> patterns = expandZeroLevelDoubleStarPatterns(normalized);
        List<PathMatcher> matchers = patterns.stream()
            .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
            .toList();
        walkAndCollect(rootPath, searchRoot, matchers, ignorePrefixes, fileFilter, uniqueFiles);
    }

    static List<String> expandZeroLevelDoubleStarPatterns(String pattern) {
        List<String> results = new ArrayList<>();
        results.add(pattern);
        int idx = pattern.indexOf("**/");
        if (idx >= 0) {
            String withoutDoubleStar = pattern.substring(0, idx) + pattern.substring(idx + 3);
            results.addAll(expandZeroLevelDoubleStarPatterns(withoutDoubleStar));
        }
        return results.stream().distinct().toList();
    }

    private void scanExactPattern(
        Path rootPath,
        String normalized,
        Set<String> ignorePrefixes,
        Predicate<Path> fileFilter,
        Map<Path, RepoPath> uniqueFiles
    ) {
        Path path = rootPath.resolve(normalized);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || isIgnored(rootPath, path, ignorePrefixes)) {
            return;
        }
        RepoPath repoPath = RepoPath.of(path, rootPath);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            walkAndCollect(rootPath, path, null, ignorePrefixes, fileFilter, uniqueFiles);
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && (fileFilter == null || fileFilter.test(path))) {
            uniqueFiles.putIfAbsent(repoPath.toPath(), repoPath);
        }
    }

    private void walkAndCollect(
        Path rootPath,
        Path searchRoot,
        List<PathMatcher> matchers,
        Set<String> ignorePrefixes,
        Predicate<Path> fileFilter,
        Map<Path, RepoPath> uniqueFiles
    ) {
        try {
            Files.walkFileTree(searchRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isIgnored(rootPath, dir, ignorePrefixes) || attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink() && !Files.isSymbolicLink(file) && !isIgnored(rootPath, file, ignorePrefixes)) {
                        Path rel = rootPath.relativize(file);
                        String relStr = rel.toString().replace('\\', '/');
                        boolean matchesPattern = (matchers == null || matchers.isEmpty())
                            || matchers.stream().anyMatch(m -> m.matches(Path.of(relStr)));
                        if (matchesPattern && (fileFilter == null || fileFilter.test(file))) {
                            RepoPath repoPath = RepoPath.of(file, rootPath);
                            uniqueFiles.putIfAbsent(repoPath.toPath(), repoPath);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk search root: " + searchRoot, e);
        }
    }

    private static int findFirstWildcard(String str) {
        int star = str.indexOf('*');
        int q = str.indexOf('?');
        if (star >= 0 && q >= 0) {
            return Math.min(star, q);
        }
        return star >= 0 ? star : q;
    }

    private static boolean isIgnored(Path root, Path target, Set<String> ignorePrefixes) {
        if (root.equals(target)) {
            return false;
        }
        Path rel = root.relativize(target);
        String relStr = rel.toString().replace('\\', '/');
        for (String prefix : ignorePrefixes) {
            String normPrefix = prefix.replace('\\', '/');
            if (relStr.equals(normPrefix) || relStr.startsWith(normPrefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
