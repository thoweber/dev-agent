package org.quarkus.agent.entity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;
import org.quarkus.agent.testutil.TaskRunMother;
import org.quarkus.agent.workspace.ProjectIdResolver;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class StatePersistenceIT {

    @Inject
    ProjectIdResolver projectResolver;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            StepAuditEntity.deleteAll();
            TaskRunEntity.deleteAll();
        });
    }

    @Test
    @DisplayName("Given tasks across multiple projects - when queried by project_id - then isolates data without cross-talk")
    void testMultiTenantIsolation() {
        QuarkusTransaction.requiringNew().run(() -> {
            // Clean up previous runs if any
            StepAuditEntity.deleteAll();
            TaskRunEntity.deleteAll();

            // Seed Project A task
            TaskRunEntity taskA = new TaskRunEntity();
            taskA.projectId = "thoweber/omnidepot";
            taskA.taskId = "TASK-001";
            taskA.status = TaskStatus.PLANNING;
            taskA.currentPhase = "PLANNING";
            taskA.createdAt = Instant.now();
            taskA.updatedAt = Instant.now();
            taskA.persist();

            // Seed Project B task
            TaskRunEntity taskB = new TaskRunEntity();
            taskB.projectId = "thoweber/store-front";
            taskB.taskId = "TASK-001"; // Same taskId, different projectId
            taskB.status = TaskStatus.GREEN_PATCH;
            taskB.currentPhase = "GREEN_PATCH";
            taskB.createdAt = Instant.now();
            taskB.updatedAt = Instant.now();
            taskB.persist();
        });

        // Verify Project A query
        TaskRunEntity retrievedA = QuarkusTransaction.requiringNew().call(() ->
            TaskRunEntity.findByProjectAndTask("thoweber/omnidepot", "TASK-001")
        );
        assertThat(retrievedA).isNotNull();
        assertThat(retrievedA.projectId).isEqualTo("thoweber/omnidepot");
        assertThat(retrievedA.status).isEqualTo(TaskStatus.PLANNING);

        // Verify Project B query
        TaskRunEntity retrievedB = QuarkusTransaction.requiringNew().call(() ->
            TaskRunEntity.findByProjectAndTask("thoweber/store-front", "TASK-001")
        );
        assertThat(retrievedB).isNotNull();
        assertThat(retrievedB.projectId).isEqualTo("thoweber/store-front");
        assertThat(retrievedB.status).isEqualTo(TaskStatus.GREEN_PATCH);

        // Verify count per project
        long countA = QuarkusTransaction.requiringNew().call(() ->
            TaskRunEntity.count("projectId", "thoweber/omnidepot")
        );
        assertThat(countA).isEqualTo(1);
    }

    @Inject
    jakarta.enterprise.event.Event<org.quarkus.agent.model.TaskStateChangeEvent> stateChangeEvent;

    @Inject
    org.quarkus.agent.workspace.WorkspaceContextResolver workspaceResolver;

    @Test
    @DisplayName("Given state change event - when fired - then updates H2 and projects Markdown journal")
    void testStateChangeEventProjectsMarkdown() throws Exception {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            TaskRunEntity task = new TaskRunEntity();
            task.projectId = "thoweber/dev-agent";
            task.taskId = "TASK-PROJECTION-01";
            task.userStory = "Verify dual representation";
            task.status = TaskStatus.PLANNING;
            task.currentPhase = "PLANNING";
            task.createdAt = Instant.now();
            task.updatedAt = Instant.now();
            task.persist();
        });

        stateChangeEvent.fire(new org.quarkus.agent.model.TaskStateChangeEvent(
            "thoweber/dev-agent",
            "TASK-PROJECTION-01",
            TaskStatus.RED_TEST,
            "RED_TEST",
            "Added failing test with password=hunter2",
            Instant.now()
        ));

        TaskRunEntity updated = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            TaskRunEntity t = TaskRunEntity.findByProjectAndTask("thoweber/dev-agent", "TASK-PROJECTION-01");
            if (t != null && t.steps != null) {
                t.steps.size(); // initialize collection
            }
            return t;
        });
        assertThat(updated).isNotNull();
        assertThat(updated.status).isEqualTo(TaskStatus.RED_TEST);
        assertThat(updated.steps).isNotEmpty();
        assertThat(updated.steps.get(0).outputSummary)
            .doesNotContain("hunter2")
            .contains("***REDACTED_CREDENTIAL***");

        java.nio.file.Path journalPath = workspaceResolver.resolveAgentsDir()
            .resolve("runs/TASK-PROJECTION-01.md");
        assertThat(journalPath).isRegularFile();
        String content = java.nio.file.Files.readString(journalPath);
        assertThat(content).contains("TASK-PROJECTION-01")
            .contains("RED_TEST")
            .contains("Verify dual representation")
            .contains("Added failing test");

        // Clean up generated test run journal
        java.nio.file.Files.deleteIfExists(journalPath);
    }

    @Inject
    TaskPersistenceService persistenceService;

    @Test
    @DisplayName("Given TaskPersistenceService - maps TaskRunEntity and StepAuditEntity to domain models")
    void testTaskPersistenceServiceDomainMapping() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            StepAuditEntity.deleteAll();
            TaskRunEntity.deleteAll();

            TaskRunEntity task = new TaskRunEntity();
            task.projectId = "map-org/map-repo";
            task.taskId = "TASK-MAP-01";
            task.status = TaskStatus.PLANNING;
            task.currentPhase = "PLANNING";
            task.branchName = "feature/test";
            task.baseCommit = "sha123";
            task.pid = 42L;
            task.createdAt = Instant.now();
            task.updatedAt = Instant.now();
            task.persist();

            StepAuditEntity step = new StepAuditEntity();
            step.taskRun = task;
            step.toolName = "compile";
            step.durationMs = 100L;
            step.exitCode = 0;
            step.createdAt = Instant.now();
            step.setOutputSummary(new org.quarkus.agent.workspace.SanitizedOutput("Success"));
            step.persist();

            task.steps.add(step);
            task.persist();
        });

        org.quarkus.agent.model.TaskRun domain = persistenceService.findByProjectAndTask("map-org/map-repo", "TASK-MAP-01").orElse(null);
        assertThat(domain).isNotNull();
        assertThat(domain.projectId()).isEqualTo("map-org/map-repo");
        assertThat(domain.taskId()).isEqualTo("TASK-MAP-01");
        assertThat(domain.branch()).isEqualTo("feature/test");
        assertThat(domain.steps()).hasSize(1);
        assertThat(domain.steps().get(0).toolName()).isEqualTo("compile");
        assertThat(domain.steps().get(0).outputSummary()).isEqualTo("Success");
    }

    @Test
    @DisplayName("Given StepAuditEntity - when setOutputSummary called with SanitizedOutput - populates output safely")
    void testStepAuditEntityConsumesSanitizedOutput() {
        StepAuditEntity entity = new StepAuditEntity();
        org.quarkus.agent.workspace.SanitizedOutput input = new org.quarkus.agent.workspace.SanitizedOutput("git push");
        org.quarkus.agent.workspace.SanitizedOutput summary = new org.quarkus.agent.workspace.SanitizedOutput("Error: ***REDACTED_CREDENTIAL***");

        entity.setSanitizedInput(input);
        entity.setOutputSummary(summary);

        assertThat(entity.sanitizedInput).isEqualTo("git push");
        assertThat(entity.outputSummary).isEqualTo("Error: ***REDACTED_CREDENTIAL***");
    }

    @Test
    @DisplayName("Given TaskPersistenceService in live container - saves and deletes all entities")
    void testLiveContainerPersistenceOperations() {
        QuarkusTransaction.requiringNew().run(() -> {
            persistenceService.deleteAll();
            TaskRun taskRun = TaskRunMother.builder("TASK-SAVE-IT")
                .projectId("it-org/it-repo")
                .status(TaskStatus.GREEN_PATCH)
                .currentPhase("GREEN_PATCH")
                .build();
            org.quarkus.agent.model.TaskRun saved = persistenceService.saveTask(taskRun);
            assertThat(saved).isNotNull();
            assertThat(saved.taskId()).isEqualTo("TASK-SAVE-IT");

            persistenceService.deleteAll();
        });
    }
}
