package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

/**
 * Manages dedicated Git worktrees under {@code .git/dev-agent-worktrees/<task-id>/},
 * ephemeral working-tree hydration, rollback checkpoints, and worktree cleanup.
 */
@ApplicationScoped
public class GitWorktreeService {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SLUG_LENGTH = 48;

    private final SubprocessExecutor subprocessExecutor;
    private final GitExcludeManager gitExcludeManager;

    @Inject
    public GitWorktreeService(SubprocessExecutor subprocessExecutor, GitExcludeManager gitExcludeManager) {
        this.subprocessExecutor = subprocessExecutor;
        this.gitExcludeManager = gitExcludeManager;
    }

    public GitWorktreeService() {
        this(new SubprocessExecutor(), new GitExcludeManager());
    }

    public boolean isGitRepository(Path dir) {
        if (isNull(dir) || !Files.isDirectory(dir)) {
            return false;
        }
        ProcessResult result = subprocessExecutor.execute(
            List.of("git", "rev-parse", "--is-inside-work-tree"),
            dir,
            Duration.ofSeconds(5)
        );
        return result.isSuccess() && "true".equalsIgnoreCase(result.stdOut().trim());
    }

    public WorktreeAllocation allocateWorktree(
        Path repoRoot,
        String taskId,
        String storySlug,
        DevAgentConfig.IsolationConfig isolation,
        String targetDir
    ) {
        validateTaskId(taskId);
        Path canonicalRoot = resolveCanonicalRoot(repoRoot);
        gitExcludeManager.ensureExcluded(canonicalRoot, targetDir);

        Path worktreesRoot = canonicalRoot.resolve(".git").resolve("dev-agent-worktrees");
        Path worktreeDir = worktreesRoot.resolve(taskId);
        StateSocketService.validateWorktreePathSymlinks(canonicalRoot, worktreeDir);

        String baseCommit = resolveHeadCommit(canonicalRoot);
        String template = !isNull(isolation) ? isolation.branchTemplate() : "feature/{task-id}-{story-slug}";
        String branchName = renderBranchName(template, taskId, storySlug);

        createOrAttachWorktree(canonicalRoot, worktreeDir, branchName, baseCommit);

        boolean autoStash = isNull(isolation) || isolation.autoStashUncommitted();
        String checkpointStash = autoStash ? hydrateUncommittedChanges(canonicalRoot, worktreeDir) : null;

        return WorktreeAllocation.builder()
            .taskId(taskId)
            .branch(branchName)
            .worktreePath(worktreeDir)
            .baseCommit(baseCommit)
            .checkpointStash(checkpointStash)
            .hydrated(checkpointStash != null)
            .build();
    }

    public boolean commitChanges(Path worktreePath, String message) {
        ProcessResult status = subprocessExecutor.execute(List.of("git", "status", "--porcelain"), worktreePath, GIT_TIMEOUT);
        if (!status.isSuccess()) {
            throw new WorkspaceException("Failed to inspect worktree git status: " + status.stdErr());
        }
        if (status.stdOut().isBlank()) {
            return false;
        }
        ProcessResult add = subprocessExecutor.execute(List.of("git", "add", "-A"), worktreePath, GIT_TIMEOUT);
        if (!add.isSuccess()) {
            throw new WorkspaceException("Failed to stage worktree changes: " + add.stdErr());
        }
        ProcessResult commit = subprocessExecutor.execute(List.of("git", "commit", "-m", message), worktreePath, GIT_TIMEOUT);
        if (!commit.isSuccess()) {
            throw new WorkspaceException("Failed to commit worktree changes: " + commit.stdErr());
        }
        return true;
    }

    public void resetToBase(Path worktreePath, String baseCommit) {
        resetToBase(worktreePath, baseCommit, BackendBuildService.DEFAULT_TARGET_DIR);
    }

    public void resetToBase(Path worktreePath, String baseCommit, String targetDir) {
        ProcessResult reset = subprocessExecutor.execute(List.of("git", "reset", "--hard", baseCommit), worktreePath, GIT_TIMEOUT);
        if (!reset.isSuccess()) {
            throw new WorkspaceException("Failed to reset worktree to " + baseCommit + ": " + reset.stdErr());
        }
        String effectiveTarget = (!isNull(targetDir) && !targetDir.isBlank()) ? targetDir.trim() : BackendBuildService.DEFAULT_TARGET_DIR;
        ProcessResult clean = subprocessExecutor.execute(
            List.of("git", "clean", "-fd", "-e", "state.json", "-e", "worker.log", "-e", effectiveTarget, "-e", ".cache-agent"),
            worktreePath,
            GIT_TIMEOUT
        );
        if (!clean.isSuccess()) {
            throw new WorkspaceException("Failed to clean worktree untracked files: " + clean.stdErr());
        }
    }

    public void removeWorktree(Path repoRoot, String taskId, boolean deleteBranch, String branchName) {
        validateTaskId(taskId);
        Path canonicalRoot = resolveCanonicalRoot(repoRoot);
        Path worktreeDir = canonicalRoot.resolve(".git").resolve("dev-agent-worktrees").resolve(taskId);
        StateSocketService.validateWorktreePathSymlinks(canonicalRoot, worktreeDir);

        if (Files.exists(worktreeDir)) {
            subprocessExecutor.execute(
                List.of("git", "worktree", "remove", "--force", worktreeDir.toString()),
                canonicalRoot,
                GIT_TIMEOUT
            );
            deleteDirectoryQuietly(worktreeDir);
        }
        subprocessExecutor.execute(List.of("git", "worktree", "prune"), canonicalRoot, GIT_TIMEOUT);

        if (deleteBranch && !isNull(branchName) && !branchName.isBlank()) {
            ProcessResult branchDelete = subprocessExecutor.execute(
                List.of("git", "branch", "-D", branchName),
                canonicalRoot,
                GIT_TIMEOUT
            );
            if (!branchDelete.isSuccess()) {
                throw new WorkspaceException("Failed to delete branch " + branchName + ": " + branchDelete.stdErr());
            }
        }
    }

    public List<String> listWorktreeTaskIds(Path repoRoot) {
        Path worktreesRoot = resolveCanonicalRoot(repoRoot).resolve(".git").resolve("dev-agent-worktrees");
        if (!Files.isDirectory(worktreesRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(worktreesRoot)) {
            return entries
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new WorkspaceException("Failed to list worktrees under " + worktreesRoot, e);
        }
    }

    private String resolveHeadCommit(Path repoRoot) {
        ProcessResult result = subprocessExecutor.execute(List.of("git", "rev-parse", "HEAD"), repoRoot, GIT_TIMEOUT);
        if (!result.isSuccess() || result.stdOut().isBlank()) {
            throw new WorkspaceException("Failed to resolve HEAD commit in " + repoRoot + ": " + result.stdErr());
        }
        return result.stdOut().trim();
    }

    private void createOrAttachWorktree(Path repoRoot, Path worktreeDir, String branchName, String baseCommit) {
        try {
            Files.createDirectories(worktreeDir.getParent());
        } catch (IOException e) {
            throw new WorkspaceException("Failed to create worktrees parent directory", e);
        }

        subprocessExecutor.execute(List.of("git", "worktree", "prune"), repoRoot, GIT_TIMEOUT);

        ProcessResult branchCheck = subprocessExecutor.execute(
            List.of("git", "show-ref", "--verify", "--quiet", "refs/heads/" + branchName),
            repoRoot,
            GIT_TIMEOUT
        );

        List<String> cmd = branchCheck.isSuccess()
            ? List.of("git", "worktree", "add", worktreeDir.toString(), branchName)
            : List.of("git", "worktree", "add", "-b", branchName, worktreeDir.toString(), baseCommit);

        ProcessResult addResult = subprocessExecutor.execute(cmd, repoRoot, GIT_TIMEOUT);
        if (!addResult.isSuccess()) {
            throw new WorkspaceException("Failed to allocate git worktree at " + worktreeDir + ": " + addResult.stdErr());
        }
    }

    private String hydrateUncommittedChanges(Path repoRoot, Path worktreeDir) {
        ProcessResult status = subprocessExecutor.execute(List.of("git", "status", "--porcelain"), repoRoot, GIT_TIMEOUT);
        if (!status.isSuccess() || status.stdOut().isBlank()) {
            return null;
        }
        ProcessResult stashCreate = subprocessExecutor.execute(List.of("git", "stash", "create"), repoRoot, GIT_TIMEOUT);
        if (!stashCreate.isSuccess() || stashCreate.stdOut().isBlank()) {
            return null;
        }
        String stashSha = stashCreate.stdOut().trim();
        ProcessResult apply = subprocessExecutor.execute(List.of("git", "stash", "apply", stashSha), worktreeDir, GIT_TIMEOUT);
        if (!apply.isSuccess()) {
            throw new WorkspaceException("Failed to hydrate uncommitted changes into worktree: " + apply.stdErr());
        }
        return stashSha;
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new WorkspaceException("Failed to remove residual worktree directory: " + dir, e);
        }
    }

    private Path resolveCanonicalRoot(Path repoRoot) {
        if (isNull(repoRoot)) {
            throw new IllegalArgumentException("repoRoot must not be null");
        }
        try {
            return repoRoot.toRealPath();
        } catch (IOException _) {
            return repoRoot.toAbsolutePath().normalize();
        }
    }

    private void validateTaskId(String taskId) {
        StateSocketService.validateTaskId(taskId);
    }

    private String renderBranchName(String template, String taskId, String storySlug) {
        String effectiveTemplate = (!isNull(template) && !template.isBlank()) ? template : "feature/{task-id}-{story-slug}";
        String sanitizedSlug = sanitizeSlug(storySlug);
        return effectiveTemplate
            .replace("{task-id}", taskId)
            .replace("{story-slug}", sanitizedSlug);
    }

    private String sanitizeSlug(String storySlug) {
        if (isNull(storySlug) || storySlug.isBlank()) {
            return "task";
        }
        String normalized = storySlug.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) {
            return "task";
        }
        return normalized.length() > MAX_SLUG_LENGTH ? normalized.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "") : normalized;
    }
}
