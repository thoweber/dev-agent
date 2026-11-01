package org.quarkus.agent.workspace;

import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.StepAudit;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStateChangeEvent;
import org.quarkus.agent.model.TaskStatus;
import org.quarkus.agent.testutil.StepAuditMother;
import org.quarkus.agent.testutil.TaskRunMother;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StateSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid TaskRun with full metadata - when projectToMarkdown called - then writes journal with all fields")
    void testProjectToMarkdownComplete() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();
        StateSyncService service = new StateSyncService(workspace, secretFilter, null);

        StepAudit step1 = StepAuditMother.builder("mvn_test")
            .exitCode(0)
            .durationMs(500L)
            .outputSummary("Tests passed")
            .build();

        StepAudit step2 = StepAuditMother.builder("git_commit")
            .exitCode(1)
            .durationMs(120L)
            .outputSummary(null)
            .build();

        TaskRun taskRun = TaskRunMother.builder("TASK-001")
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

        TaskRun taskRun = TaskRunMother.planning("org/repo", "TASK-002")
            .toBuilder()
            .branch(null)
            .updatedAt(null)
            .pid(null)
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

        TaskRun taskRun = TaskRunMother.planning("org/repo", "TASK-003")
            .toBuilder()
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

        TaskRun taskRun = TaskRunMother.planning("org/repo", "../../evil-task");

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

        TaskRun taskRun = TaskRunMother.planning("org/repo", "TASK-SYMLINK");

        assertThatThrownBy(() -> service.projectToMarkdown(taskRun))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Symbolic links are forbidden");

        // Verify outside target was not overwritten
        assertThat(Files.readString(outsideTarget)).isEqualTo("original-content");
    }

    @Test
    @DisplayName("Given TaskStateChangeEvent - when onStateTransition called - invokes persistence and projects to markdown")
    void testOnStateTransition() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();

        TaskRun recordedRun = TaskRunMother.planning("org/repo", "TASK-SYNC-EVENT")
            .toBuilder()
            .userStory("Story")
            .build();

        TaskPersistenceService mockPersistence = new TaskPersistenceService() {
            @Override
            public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
                return recordedRun;
            }
        };

        StateSyncService service = new StateSyncService(workspace, secretFilter, mockPersistence);

        TaskStateChangeEvent event = TaskStateChangeEvent.builder()
            .taskId("TASK-SYNC-EVENT")
            .projectId("org/repo")
            .newStatus(TaskStatus.PLANNING)
            .phaseName("PLANNING")
            .stepSummary("password=mySecret")
            .build();

        service.onStateTransition(event);

        Path journalFile = workspace.resolveAgentsDir().resolve("runs").resolve("TASK-SYNC-EVENT.md");
        assertThat(Files.exists(journalFile)).isTrue();
        assertThat(Files.readString(journalFile)).contains("task_id: \"TASK-SYNC-EVENT\"");
    }

    @Test
    @DisplayName("Given active transaction registry - schedules post-commit synchronization and runs callbacks")
    @SuppressWarnings("unchecked")
    void testScheduleMarkdownProjectionWithActiveTransaction() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();

        TaskRun recordedRun = TaskRunMother.planning("org/repo", "TASK-TX-SYNC");

        TaskPersistenceService mockPersistence = new TaskPersistenceService() {
            @Override
            public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
                return recordedRun;
            }
        };

        Instance<TransactionSynchronizationRegistry> registryInstance = mock(Instance.class);
        TransactionSynchronizationRegistry registry = mock(TransactionSynchronizationRegistry.class);

        when(registryInstance.isResolvable()).thenReturn(true);
        when(registryInstance.get()).thenReturn(registry);
        when(registry.getTransactionStatus()).thenReturn(Status.STATUS_ACTIVE);

        StateSyncService service = new StateSyncService(workspace, secretFilter, mockPersistence, registryInstance);

        TaskStateChangeEvent event = TaskStateChangeEvent.builder()
            .taskId("TASK-TX-SYNC")
            .projectId("org/repo")
            .newStatus(TaskStatus.PLANNING)
            .phaseName("PLANNING")
            .build();

        service.onStateTransition(event);

        Path journalFile = workspace.resolveAgentsDir().resolve("runs/TASK-TX-SYNC.md");
        assertThat(Files.exists(journalFile)).isFalse();

        ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);
        verify(registry).registerInterposedSynchronization(captor.capture());

        Synchronization synchronization = captor.getValue();
        synchronization.beforeCompletion(); // no-op test

        synchronization.afterCompletion(Status.STATUS_ROLLEDBACK);
        assertThat(Files.exists(journalFile)).isFalse();

        synchronization.afterCompletion(Status.STATUS_COMMITTED);
        assertThat(Files.exists(journalFile)).isTrue();
    }

    @Test
    @DisplayName("Given transaction registry inactive or throwing - projects markdown immediately")
    @SuppressWarnings("unchecked")
    void testScheduleMarkdownProjectionWithInactiveOrFailingRegistry() {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();

        TaskRun recordedRun = TaskRunMother.planning("org/repo", "TASK-TX-INACTIVE");

        TaskPersistenceService mockPersistence = new TaskPersistenceService() {
            @Override
            public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
                return recordedRun;
            }
        };

        Instance<TransactionSynchronizationRegistry> registryInstance = mock(Instance.class);
        TransactionSynchronizationRegistry registry = mock(TransactionSynchronizationRegistry.class);

        when(registryInstance.isResolvable()).thenReturn(true);
        when(registryInstance.get()).thenReturn(registry);
        when(registry.getTransactionStatus()).thenReturn(Status.STATUS_NO_TRANSACTION);

        StateSyncService service = new StateSyncService(workspace, secretFilter, mockPersistence, registryInstance);

        TaskStateChangeEvent event = TaskStateChangeEvent.builder()
            .taskId("TASK-TX-INACTIVE")
            .projectId("org/repo")
            .newStatus(TaskStatus.PLANNING)
            .phaseName("PLANNING")
            .build();

        service.onStateTransition(event);

        Path journalFile = workspace.resolveAgentsDir().resolve("runs/TASK-TX-INACTIVE.md");
        assertThat(Files.exists(journalFile)).isTrue();

        // Test exception branch
        when(registry.getTransactionStatus()).thenThrow(new RuntimeException("Simulated registry error"));
        TaskRun recordedRun2 = recordedRun.toBuilder().taskId("TASK-TX-FAIL").build();
        TaskPersistenceService mockPersistence2 = new TaskPersistenceService() {
            @Override
            public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
                return recordedRun2;
            }
        };

        StateSyncService service2 = new StateSyncService(workspace, secretFilter, mockPersistence2, registryInstance);
        service2.onStateTransition(event.toBuilder().taskId("TASK-TX-FAIL").build());
        Path journalFile2 = workspace.resolveAgentsDir().resolve("runs/TASK-TX-FAIL.md");
        assertThat(Files.exists(journalFile2)).isTrue();
    }

    @Test
    @DisplayName("Given null persistence result or null taskId - handles safely")
    void testEdgeCasesAndNullValidation() {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        SecretRedactionFilter secretFilter = new SecretRedactionFilter();

        TaskPersistenceService nullPersistence = new TaskPersistenceService() {
            @Override
            public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
                return null;
            }
        };

        StateSyncService service = new StateSyncService(workspace, secretFilter, nullPersistence);
        TaskStateChangeEvent event = TaskStateChangeEvent.builder()
            .taskId("TASK-NULL")
            .projectId("org/repo")
            .newStatus(TaskStatus.PLANNING)
            .phaseName("PLANNING")
            .stepSummary("") // blank summary
            .build();

        // Should return early and not project markdown
        service.onStateTransition(event);
        Path journalFile = workspace.resolveAgentsDir().resolve("runs/TASK-NULL.md");
        assertThat(Files.exists(journalFile)).isFalse();

        // Test registryInstance not resolvable branch
        Instance<TransactionSynchronizationRegistry> unresolvableRegistry = mock(Instance.class);
        when(unresolvableRegistry.isResolvable()).thenReturn(false);
        TaskRun recordedRun = TaskRunMother.planning("TASK-UNRESOLVABLE");
        TaskPersistenceService mockPersist = new TaskPersistenceService() {
            @Override
            public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
                return recordedRun;
            }
        };
        StateSyncService unresolvableService = new StateSyncService(workspace, secretFilter, mockPersist, unresolvableRegistry);
        unresolvableService.onStateTransition(event.toBuilder().taskId("TASK-UNRESOLVABLE").stepSummary("   ").build());
        assertThat(Files.exists(workspace.resolveAgentsDir().resolve("runs/TASK-UNRESOLVABLE.md"))).isTrue();

        // Validate null taskId throws SecurityException
        TaskRun nullTaskRun = TaskRunMother.builder(null).branch(null).build();
        assertThatThrownBy(() -> service.projectToMarkdown(nullTaskRun))
            .isInstanceOf(SecurityException.class);

        // Validate default constructor
        StateSyncService defaultService = new StateSyncService();
        assertThat(defaultService).isNotNull();
    }
}
