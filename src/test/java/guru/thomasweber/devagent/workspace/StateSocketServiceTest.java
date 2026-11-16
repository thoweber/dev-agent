package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateSocketServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given WorktreeState with secret in message - when writeHeartbeat and readState called - then writes atomically and redacts credentials")
    void givenWorktreeStateWithSecret_whenWriteAndRead_thenWritesAtomicallyAndRedactsSecret() throws IOException {
        StateSocketService service = new StateSocketService();
        WorktreeState state = WorktreeStateMother.active("TASK-501", ProcessHandle.current().pid())
            .toBuilder()
            .lastMessage("Auth failed with token ghp_1234567890abcdefghijklmnopqrstuvwxyz1234")
            .build();

        service.writeHeartbeat(tempDir, state);

        Path stateFile = service.resolveStateFile(tempDir, "TASK-501");
        assertThat(stateFile).isRegularFile();
        assertThat(stateFile.getParent().resolve("state.json.tmp")).doesNotExist();

        Optional<WorktreeState> loaded = service.readState(tempDir, "TASK-501");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().taskId()).isEqualTo("TASK-501");
        assertThat(loaded.get().status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(loaded.get().lastMessage()).doesNotContain("ghp_1234567890").contains("***REDACTED_CREDENTIAL***");
        assertThat(service.isProcessAlive(loaded.get())).isTrue();
    }

    @Test
    @DisplayName("Given multiple worktree state sockets - when readAllStates called - then returns sorted list descending by updatedAt")
    void givenMultipleWorktreeStates_whenReadAllStates_thenReturnsSortedDescending() throws IOException {
        StateSocketService service = new StateSocketService();
        Instant older = Instant.parse("2026-11-16T10:00:00Z");
        Instant newer = Instant.parse("2026-11-16T12:00:00Z");

        service.writeHeartbeat(tempDir, WorktreeStateMother.builder("TASK-OLD").updatedAt(older).build());
        service.writeHeartbeat(tempDir, WorktreeStateMother.builder("TASK-NEW").updatedAt(newer).build());
        // Create malformed state.json that should be skipped gracefully
        Path malformedDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-BAD"));
        Files.writeString(malformedDir.resolve("state.json"), "{not-valid-json");

        List<WorktreeState> states = service.readAllStates(tempDir);
        assertThat(states).extracting(WorktreeState::taskId).containsExactly("TASK-NEW", "TASK-OLD");
        assertThat(service.readState(tempDir, "TASK-MISSING")).isEmpty();
        assertThat(service.readState(tempDir, "TASK-BAD")).isEmpty();
        assertThat(service.resolveWorkerLogFile(tempDir, "TASK-NEW"))
            .isEqualTo(tempDir.toRealPath().resolve(".git/dev-agent-worktrees/TASK-NEW/worker.log"));
    }

    @Test
    @DisplayName("Given traversal taskId or symlinked state path - when accessed - then throws SecurityException")
    void givenTraversalOrSymlink_whenAccessed_thenThrowsSecurityException() throws IOException {
        StateSocketService service = new StateSocketService();

        assertThatThrownBy(() -> service.resolveStateFile(tempDir, "../escape"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.resolveStateFile(tempDir, "bad/id"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.resolveWorkerLogFile(tempDir, "  "))
            .isInstanceOf(SecurityException.class);

        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-SYM"));
        Path externalFile = Files.createTempFile("ext-state", ".json");
        Files.createSymbolicLink(worktreeDir.resolve("state.json"), externalFile);

        assertThatThrownBy(() -> service.writeHeartbeat(tempDir, WorktreeStateMother.queued("TASK-SYM")))
            .isInstanceOf(SecurityException.class);
        Files.deleteIfExists(externalFile);
    }

    @Test
    @DisplayName("Given WorktreeState with dead or null PID - when isProcessAlive called - then returns false")
    void givenDeadOrNullPid_whenIsProcessAlive_thenReturnsFalse() {
        StateSocketService service = new StateSocketService();
        assertThat(service.isProcessAlive(null)).isFalse();
        assertThat(service.isProcessAlive(WorktreeStateMother.queued("TASK-Q"))).isFalse();
        assertThat(service.isProcessAlive(WorktreeStateMother.crashed("TASK-C"))).isFalse();
        assertThat(service.readAllStates(tempDir)).isEmpty();
    }

    @Test
    @DisplayName("Given null state, blank message, null updatedAt, or directory named state.json - when invoked - then handles edge cases safely")
    void givenEdgeCases_whenInvoked_thenHandlesSafely() throws IOException {
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);
        StateSocketService service = new StateSocketService(new SecretRedactionFilter(), resolver);

        assertThatThrownBy(() -> service.writeHeartbeat(tempDir, null))
            .isInstanceOf(IllegalArgumentException.class);

        WorktreeState blankMsg = WorktreeStateMother.builder("TASK-EDGE")
            .lastMessage("   ")
            .updatedAt(null)
            .build();
        service.writeHeartbeat(null, blankMsg);

        Optional<WorktreeState> loaded = service.readState(null, "TASK-EDGE");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().updatedAt()).isNotNull();
        assertThat(loaded.get().lastMessage()).isEqualTo("   ");

        // Directory named state.json is ignored gracefully
        Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-DIR/state.json"));
        assertThat(service.readState(tempDir, "TASK-DIR")).isEmpty();
    }

    @Test
    @DisplayName("Given dot taskId or symlinked .git root - when resolved - then throws SecurityException")
    void givenDotTaskIdOrDotGitSymlink_whenResolved_thenThrowsSecurityException() throws IOException {
        StateSocketService service = new StateSocketService();
        assertThatThrownBy(() -> service.resolveStateFile(tempDir, "."))
            .isInstanceOf(SecurityException.class);

        Path externalDir = Files.createDirectories(tempDir.resolve("external-git"));
        Path repo = Files.createDirectories(tempDir.resolve("repo-symlink-git"));
        Files.createSymbolicLink(repo.resolve(".git"), externalDir);

        assertThatThrownBy(() -> service.resolveStateFile(repo, "TASK-SYM"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Given branch with secret and mismatched pidStartedAt - when writeHeartbeat or isProcessAlive called - then redacts branch and rejects recycled PID")
    void givenSecretBranchAndStalePidStart_whenInvoked_thenSanitizesAndDetectsReuse() {
        StateSocketService service = new StateSocketService();
        WorktreeState dirtyBranch = WorktreeStateMother.builder("TASK-REDACT-BR")
            .branch("feature/ghp_1234567890abcdef1234567890abcdef1234")
            .lastMessage("ok")
            .build();
        service.writeHeartbeat(tempDir, dirtyBranch);

        assertThat(service.readState(tempDir, "TASK-REDACT-BR"))
            .isPresent()
            .get()
            .extracting(WorktreeState::branch)
            .asString()
            .doesNotContain("ghp_1234567890abcdef1234567890abcdef1234");

        long currentPid = ProcessHandle.current().pid();
        WorktreeState reusedPidState = WorktreeStateMother.active("TASK-REUSED", currentPid)
            .toBuilder()
            .pidStartedAt(Instant.now().minusSeconds(7200))
            .build();
        assertThat(service.isProcessAlive(reusedPidState)).isFalse();

        WorktreeState matchingPidState = WorktreeStateMother.active("TASK-MATCH", currentPid)
            .toBuilder()
            .pidStartedAt(StateSocketService.resolvePidStartInstant(currentPid))
            .build();
        assertThat(service.isProcessAlive(matchingPidState)).isTrue();
        assertThat(StateSocketService.resolvePidStartInstant(null)).isNull();
        assertThat(StateSocketService.resolvePidStartInstant(0L)).isNull();
        assertThat(StateSocketService.matchesPidStart(null, null)).isFalse();
        assertThat(service.isProcessAlive(WorktreeStateMother.builder("TASK-ZERO").pid(0L).build())).isFalse();
    }
}
