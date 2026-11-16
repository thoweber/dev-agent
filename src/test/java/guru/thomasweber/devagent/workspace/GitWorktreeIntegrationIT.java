package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class GitWorktreeIntegrationIT {

    @TempDir
    Path tempRepo;

    @Inject
    GitWorktreeService worktreeService;

    @Inject
    StateSocketService socketService;

    @Inject
    LocalWorkerLimiter workerLimiter;

    @Inject
    SubprocessExecutor subprocessExecutor;

    @Inject
    DevAgentConfigLoader configLoader;

    @Test
    @DisplayName("Given real Git repo with uncommitted changes - when worktree allocated, executed in ScopedValue context, and removed - then preserves repo and cleans worktree")
    void givenRealGitRepo_whenWorktreeLifecycleExecuted_thenIsolatesAndCleansUp() throws IOException {
        initGitRepo(tempRepo);
        Files.writeString(tempRepo.resolve("src.txt"), "initial\n");
        runGit(tempRepo, "add", "src.txt");
        runGit(tempRepo, "commit", "-m", "initial commit");
        // Create uncommitted tracked modification to test stash hydration
        Files.writeString(tempRepo.resolve("src.txt"), "dirty local edit\n");

        DevAgentConfig config = configLoader.loadFromYaml("");
        WorktreeAllocation allocation = worktreeService.allocateWorktree(
            tempRepo,
            "TASK-IT-WT",
            "Integration Isolation",
            config.isolation(),
            "target-agent"
        );

        assertThat(allocation.worktreePath()).isDirectory();
        assertThat(allocation.branch()).isEqualTo("feature/TASK-IT-WT-integration-isolation");
        assertThat(allocation.hydrated()).isTrue();
        assertThat(Files.readString(allocation.worktreePath().resolve("src.txt"))).contains("dirty local edit");
        assertThat(Files.readString(tempRepo.resolve(".git/info/exclude"))).contains("target-agent/");

        WorktreeState state = WorktreeStateMother.active("TASK-IT-WT", ProcessHandle.current().pid()).toBuilder()
            .branch(allocation.branch())
            .worktreePath(allocation.worktreePath().toString())
            .build();
        socketService.writeHeartbeat(tempRepo, state);
        assertThat(socketService.readState(tempRepo, "TASK-IT-WT")).isPresent();

        LocalWorkerLimiter.PermitGate gate = workerLimiter.openGate(config.maxConcurrentWorkers());
        Path insideContextRoot = gate.executeWithPermit(
            tempRepo,
            state,
            () -> WorktreeExecutionContext.runInWorktree(
                allocation.worktreePath(),
                () -> new WorkspaceContextResolver(tempRepo).resolveExecutionRoot()
            )
        );
        assertThat(insideContextRoot).isEqualTo(allocation.worktreePath().toRealPath());

        // Commit inside worktree and remove worktree preserving branch
        Files.writeString(allocation.worktreePath().resolve("feature.txt"), "done\n");
        assertThat(worktreeService.commitChanges(allocation.worktreePath(), "Add feature")).isTrue();
        worktreeService.removeWorktree(tempRepo, "TASK-IT-WT", false, allocation.branch());
        assertThat(allocation.worktreePath()).doesNotExist();
        ProcessResult preserved = subprocessExecutor.execute(
            List.of("git", "branch", "--list", allocation.branch()),
            tempRepo,
            Duration.ofSeconds(5)
        );
        assertThat(preserved.stdOut()).contains(allocation.branch());

        // Second task with deleteBranch = true
        WorktreeAllocation cleanupTarget = worktreeService.allocateWorktree(
            tempRepo,
            "TASK-IT-DEL",
            "Delete Me",
            config.isolation(),
            "target-agent"
        );
        worktreeService.removeWorktree(tempRepo, "TASK-IT-DEL", true, cleanupTarget.branch());
        ProcessResult deleted = subprocessExecutor.execute(
            List.of("git", "branch", "--list", cleanupTarget.branch()),
            tempRepo,
            Duration.ofSeconds(5)
        );
        assertThat(deleted.stdOut().trim()).isEmpty();
    }

    private void initGitRepo(Path dir) {
        runGit(dir, "init");
        runGit(dir, "config", "user.email", "it@example.com");
        runGit(dir, "config", "user.name", "IT Runner");
    }

    private void runGit(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessResult result = subprocessExecutor.execute(cmd, dir, Duration.ofSeconds(5));
        assertThat(result.isSuccess()).as("git %s failed: %s", String.join(" ", args), result.stdErr()).isTrue();
    }
}
