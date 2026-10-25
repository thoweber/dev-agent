package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quarkus.agent.entity.StepAuditEntity;
import org.quarkus.agent.entity.TaskRunEntity;
import org.quarkus.agent.entity.TaskStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid TaskRunEntity with full metadata - when projectToMarkdown called - then writes journal with all fields")
    void testProjectToMarkdownComplete() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter);

        TaskRunEntity entity = new TaskRunEntity();
        entity.taskId = "TASK-001";
        entity.projectId = "org/repo";
        entity.status = TaskStatus.GREEN_PATCH;
        entity.currentPhase = "GREEN_PATCH";
        entity.branchName = "feature/TASK-001-desc";
        entity.baseCommit = "abc1234";
        entity.checkpointStash = "stash@{0}";
        entity.pid = 12345L;
        entity.retryCount = 2;
        entity.userStory = "User story text";
        entity.approvedPlanJson = "{\"steps\":[\"step1\"]}";
        entity.updatedAt = Instant.parse("2026-10-25T12:00:00Z");

        StepAuditEntity step1 = new StepAuditEntity();
        step1.toolName = "mvn_test";
        step1.exitCode = 0;
        step1.durationMs = 500L;
        step1.outputSummary = "Tests passed";

        StepAuditEntity step2 = new StepAuditEntity();
        step2.toolName = "git_commit";
        step2.exitCode = 1;
        step2.durationMs = 120L;
        step2.outputSummary = null;

        entity.steps = List.of(step1, step2);

        service.projectToMarkdown(entity);

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
    @DisplayName("Given TaskRunEntity with null optional fields - when projectToMarkdown called - then uses sensible fallbacks")
    void testProjectToMarkdownDefaults() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter);

        TaskRunEntity entity = new TaskRunEntity();
        entity.taskId = "TASK-002";
        entity.projectId = "org/repo";
        entity.status = TaskStatus.PLANNING;
        entity.currentPhase = "PLANNING";
        entity.branchName = null;
        entity.baseCommit = null;
        entity.checkpointStash = null;
        entity.pid = null;
        entity.retryCount = 0;
        entity.userStory = null;
        entity.approvedPlanJson = null;
        entity.updatedAt = null;
        entity.steps = null;

        service.projectToMarkdown(entity);

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

        entity.steps = Collections.emptyList();
        service.projectToMarkdown(entity);
        markdown = Files.readString(journalFile);
        assertThat(markdown).contains("- No steps executed yet.");
    }

    @Test
    @DisplayName("Given secret credential in story - when projectToMarkdown called - then scrubs credentials")
    void testProjectToMarkdownScrubsSecrets() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter);

        TaskRunEntity entity = new TaskRunEntity();
        entity.taskId = "TASK-003";
        entity.projectId = "org/repo";
        entity.status = TaskStatus.PLANNING;
        entity.currentPhase = "PLANNING";
        entity.userStory = "Connecting to API with key AIzaSy123456789012345678901234567890";

        service.projectToMarkdown(entity);

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
        StateSyncService service = new StateSyncService(workspace, secretFilter);

        TaskRunEntity entity = new TaskRunEntity();
        entity.taskId = "../../evil-task";
        entity.projectId = "org/repo";
        entity.status = TaskStatus.PLANNING;
        entity.currentPhase = "PLANNING";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.projectToMarkdown(entity))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Jailbreak attempt");
    }
}
