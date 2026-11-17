package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.model.BranchName;
import guru.thomasweber.devagent.model.TaskId;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Manages dedicated Git worktrees under {@code .git/dev-agent-worktrees/<task-id>/},
 * ephemeral working-tree hydration, rollback checkpoints, and worktree cleanup.
 */
@ApplicationScoped
public class GitWorktreeService {

    private static final Logger LOG = Logger.getLogger(GitWorktreeService.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String SUBCOMMAND_WORKTREE = "worktree";

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
        return allocateWorktree(repoRoot, TaskId.of(taskId), storySlug, isolation, targetDir);
    }

    public WorktreeAllocation allocateWorktree(
        Path repoRoot,
        TaskId taskId,
        String storySlug,
        DevAgentConfig.IsolationConfig isolation,
        String targetDir
    ) {
        WorktreeLayout layout = WorktreeLayout.forRoot(repoRoot);
        Path canonicalRoot = layout.repoRoot();
        gitExcludeManager.ensureExcluded(canonicalRoot, targetDir);

        Path worktreeDir = layout.worktreeDir(taskId);

        String baseCommit = resolveHeadCommit(canonicalRoot);
        String template = BranchName.DEFAULT_TEMPLATE;
        if (nonNull(isolation)) {
            template = isolation.branchTemplate();
        }
        String branchName = BranchName.render(template, taskId, storySlug).value();

        createOrAttachWorktree(canonicalRoot, worktreeDir, branchName, baseCommit);

        String checkpointStash = null;
        if (isNull(isolation) || isolation.autoStashUncommitted()) {
            checkpointStash = hydrateUncommittedChanges(canonicalRoot, worktreeDir);
        }

        LOG.debugf(
            "Allocated worktree taskId=%s branch=%s baseCommit=%s hydrated=%s",
            taskId.value(),
            branchName,
            baseCommit,
            nonNull(checkpointStash)
        );
        return WorktreeAllocation.builder()
            .taskId(taskId.value())
            .branch(branchName)
            .worktreePath(worktreeDir)
            .baseCommit(baseCommit)
            .checkpointStash(checkpointStash)
            .hydrated(nonNull(checkpointStash))
            .build();
    }

    public boolean commitChanges(Path worktreePath, String message) {
        if (isNull(message) || message.isBlank()) {
            throw new IllegalArgumentException("Commit message must not be null or blank");
        }
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
        resetToBase(worktreePath, baseCommit, TargetDir.DEFAULT_VALUE);
    }

    public void resetToBase(Path worktreePath, String baseCommit, String targetDir) {
        if (isNull(baseCommit) || baseCommit.isBlank() || baseCommit.trim().startsWith("-")) {
            throw new SecurityException("Invalid or unsafe base commit ref: " + baseCommit);
        }
        String safeCommit = baseCommit.trim();
        ProcessResult reset = subprocessExecutor.execute(List.of("git", "reset", "--hard", safeCommit), worktreePath, GIT_TIMEOUT);
        if (!reset.isSuccess()) {
            throw new WorkspaceException("Failed to reset worktree to " + safeCommit + ": " + reset.stdErr());
        }
        String effectiveTarget = TargetDir.of(targetDir).value();
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
        removeWorktree(repoRoot, TaskId.of(taskId), deleteBranch, branchName);
    }

    public void removeWorktree(Path repoRoot, TaskId taskId, boolean deleteBranch, String branchName) {
        WorktreeLayout layout = WorktreeLayout.forRoot(repoRoot);
        Path canonicalRoot = layout.repoRoot();
        Path worktreeDir = layout.worktreeDir(taskId);

        if (Files.exists(worktreeDir)) {
            subprocessExecutor.execute(
                List.of("git", SUBCOMMAND_WORKTREE, "remove", "--force", worktreeDir.toString()),
                canonicalRoot,
                GIT_TIMEOUT
            );
            deleteDirectoryQuietly(worktreeDir);
        }
        subprocessExecutor.execute(List.of("git", SUBCOMMAND_WORKTREE, "prune"), canonicalRoot, GIT_TIMEOUT);

        if (deleteBranch && nonNull(branchName) && !branchName.isBlank()) {
            String safeBranch = BranchName.of(branchName).value();
            ProcessResult branchDelete = subprocessExecutor.execute(
                List.of("git", "branch", "-D", safeBranch),
                canonicalRoot,
                GIT_TIMEOUT
            );
            if (!branchDelete.isSuccess()) {
                throw new WorkspaceException("Failed to delete branch " + safeBranch + ": " + branchDelete.stdErr());
            }
        }
    }

    public List<String> listWorktreeTaskIds(Path repoRoot) {
        Path worktreesRoot = WorktreeLayout.forRoot(repoRoot).worktreesRoot();
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

        subprocessExecutor.execute(List.of("git", SUBCOMMAND_WORKTREE, "prune"), repoRoot, GIT_TIMEOUT);

        ProcessResult branchCheck = subprocessExecutor.execute(
            List.of("git", "show-ref", "--verify", "--quiet", "refs/heads/" + branchName),
            repoRoot,
            GIT_TIMEOUT
        );

        List<String> cmd;
        if (branchCheck.isSuccess()) {
            cmd = List.of("git", SUBCOMMAND_WORKTREE, "add", worktreeDir.toString(), branchName);
        } else {
            cmd = List.of("git", SUBCOMMAND_WORKTREE, "add", "-b", branchName, worktreeDir.toString(), baseCommit);
        }

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
}
