package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quarkus.agent.model.StepAudit;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid TaskRun with full metadata - when projectToMarkdown called - then writes journal with all fields")
    void testProjectToMarkdownComplete() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter, null);

        StepAudit step1 = StepAudit.builder()
            .toolName("mvn_test")
            .exitCode(0)
            .durationMs(500L)
            .outputSummary("Tests passed")
            .build();

        StepAudit step2 = StepAudit.builder()
            .toolName("git_commit")
            .exitCode(1)
            .durationMs(120L)
            .outputSummary(null)
            .build();

        TaskRun taskRun = TaskRun.builder()
            .taskId("TASK-001")
            .projectId("org/repo")
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .branch("feature/TASK-001-desc")
            .baseCommit("abc1234")
            .checkpointStash("stash@{0}")
            .pid(12345L)
            .retryCount(2)
            .userStory("User story text")
            .approvedPlanJson("{\"steps\":[\"step1\"]}")
            .updatedAt(Instant.parse("2026-10-25T12:00:00Z"))
            .steps(List.of(step1, step2))
            .build();

        service.projectToMarkdown(taskRun);

        Path journalFile = workspace.resolveAgentsDir().resolve("runs/TASK-001.md");
        assertThat(journalFile).isRegularFile();

        String markdown = Files.readString(journalFile);
        assertThat(markdown)
            .contains("task_id: \"TASK-001\"")
            .contains("project_id: \"org/repo\"")
            .contains("status: \"GREEN_PATCH\"")
            .contains("branch: \"feature/TASK-001-desc\"")
            .contains("base_commit: \"abc1234\"")
            .contains("checkpoint_stash: \"stash@{0}\"")
            .contains("pid: 12345")
            .contains("retry_count: 2")
            .contains("User story text")
            .contains("{\"steps\":[\"step1\"]}")
            .contains("- [x] **mvn_test** (500ms): Tests passed")
            .contains("- [ ] **git_commit** (120ms):");
    }

    @Test
    @DisplayName("Given TaskRun with null optional fields - when projectToMarkdown called - then uses sensible fallbacks")
    void testProjectToMarkdownDefaults() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter, null);

        TaskRun taskRun = TaskRun.builder()
            .taskId("TASK-002")
            .projectId("org/repo")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .build();

        service.projectToMarkdown(taskRun);

        Path journalFile = workspace.resolveAgentsDir().resolve("runs/TASK-002.md");
        assertThat(journalFile).isRegularFile();

        String markdown = Files.readString(journalFile);
        assertThat(markdown)
            .contains("branch: \"unassigned\"")
            .contains("base_commit: \"HEAD\"")
            .contains("checkpoint_stash: \"none\"")
            .contains("pid: 0")
            .contains("updated_at: \"unknown\"")
            .contains("None specified")
            .contains("{}")
            .contains("- No steps executed yet.");

        TaskRun emptyStepsTask = taskRun.toBuilder().steps(Collections.emptyList()).build();
        service.projectToMarkdown(emptyStepsTask);
        markdown = Files.readString(journalFile);
        assertThat(markdown).contains("- No steps executed yet.");
    }

    @Test
    @DisplayName("Given secret credential in story - when projectToMarkdown called - then scrubs credentials")
    void testProjectToMarkdownScrubsSecrets() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter, null);

        TaskRun taskRun = TaskRun.builder()
            .taskId("TASK-003")
            .projectId("org/repo")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .userStory("Connecting to API with key AIzaSy123456789012345678901234567890")
            .build();

        service.projectToMarkdown(taskRun);

        Path journalFile = workspace.resolveAgentsDir().resolve("runs/TASK-003.md");
        String markdown = Files.readString(journalFile);
        assertThat(markdown)
            .doesNotContain("AIzaSy123456789012345678901234567890")
            .contains("***REDACTED_CREDENTIAL***");
    }

    @Test
    @DisplayName("Given malicious taskId with path traversal - when projectToMarkdown called - then throws SecurityException")
    void testProjectToMarkdownRejectsPathTraversal() {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter, null);

        TaskRun taskRun = TaskRun.builder()
            .taskId("../../evil-task")
            .projectId("org/repo")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .build();

        assertThatThrownBy(() -> service.projectToMarkdown(taskRun))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Jailbreak attempt");
    }

    @Test
    @DisplayName("Given journal file is a symbolic link - when projectToMarkdown called - throws SecurityException and protects target")
    void testProjectToMarkdownRejectsSymbolicLink() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter, null);

        Path runsDir = workspace.resolveAgentsDir().resolve("runs");
        Files.createDirectories(runsDir);

        Path outsideTarget = tempDir.resolve("outside-target.txt");
        Files.writeString(outsideTarget, "original-content");

        Path symlinkJournal = runsDir.resolve("TASK-SYMLINK.md");
        Files.createSymbolicLink(symlinkJournal, outsideTarget);

        TaskRun taskRun = TaskRun.builder()
            .taskId("TASK-SYMLINK")
            .projectId("org/repo")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .build();

        assertThatThrownBy(() -> service.projectToMarkdown(taskRun))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Symbolic links are forbidden");

        // Verify outside target was not overwritten
        assertThat(Files.readString(outsideTarget)).isEqualTo("original-content");
    }
}
