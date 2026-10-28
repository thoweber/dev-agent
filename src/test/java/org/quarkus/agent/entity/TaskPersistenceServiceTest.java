package org.quarkus.agent.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStateChangeEvent;
import org.quarkus.agent.model.TaskStatus;
import org.quarkus.agent.workspace.SanitizedOutput;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class TaskPersistenceServiceTest {

    private final TaskPersistenceService service = new TaskPersistenceService();

    @Test
    @DisplayName("Given null entity - toDomain returns null")
    void testToDomainNull() {
        assertThat(service.toDomain(null)).isNull();
    }

    @Test
    @DisplayName("Given TaskRunEntity with steps - toDomain maps all domain fields correctly")
    void testToDomainMapping() {
        TaskRunEntity entity = new TaskRunEntity();
        entity.id = 100L;
        entity.projectId = "org/repo";
        entity.taskId = "TASK-ENTITY-1";
        entity.status = TaskStatus.GREEN_PATCH;
        entity.currentPhase = "GREEN_PATCH";
        entity.branchName = "feature/TASK-ENTITY-1";
        entity.baseCommit = "c0ffee";
        entity.checkpointStash = "stash@{1}";
        entity.pid = 4242L;
        entity.retryCount = 1;
        entity.createdAt = Instant.parse("2026-10-25T10:00:00Z");
        entity.updatedAt = Instant.parse("2026-10-25T10:05:00Z");
        entity.userStory = "User story";
        entity.approvedPlanJson = "{}";

        StepAuditEntity step = new StepAuditEntity();
        step.id = 500L;
        step.toolName = "mvn_test";
        step.sanitizedInput = "mvn test";
        step.outputSummary = "BUILD SUCCESS";
        step.durationMs = 250L;
        step.exitCode = 0;
        step.createdAt = Instant.parse("2026-10-25T10:02:00Z");

        entity.steps = List.of(step);

        TaskRun domain = service.toDomain(entity);

        assertThat(domain).isNotNull();
        assertThat(domain.id()).isEqualTo(100L);
        assertThat(domain.projectId()).isEqualTo("org/repo");
        assertThat(domain.taskId()).isEqualTo("TASK-ENTITY-1");
        assertThat(domain.status()).isEqualTo(TaskStatus.GREEN_PATCH);
        assertThat(domain.currentPhase()).isEqualTo("GREEN_PATCH");
        assertThat(domain.branch()).isEqualTo("feature/TASK-ENTITY-1");
        assertThat(domain.baseCommit()).isEqualTo("c0ffee");
        assertThat(domain.checkpointStash()).isEqualTo("stash@{1}");
        assertThat(domain.pid()).isEqualTo(4242L);
        assertThat(domain.retryCount()).isEqualTo(1);
        assertThat(domain.userStory()).isEqualTo("User story");
        assertThat(domain.approvedPlanJson()).isEqualTo("{}");

        assertThat(domain.steps()).hasSize(1);
        assertThat(domain.steps().getFirst().toolName()).isEqualTo("mvn_test");
        assertThat(domain.steps().getFirst().sanitizedInput()).isEqualTo("mvn test");
        assertThat(domain.steps().getFirst().outputSummary()).isEqualTo("BUILD SUCCESS");
    }

    @Test
    @DisplayName("Given uninitialized Panache runtime - methods gracefully catch IllegalStateException and return defaults")
    void testPanacheUninitializedGracefulDefaults() {
        ProjectId projectId = ProjectId.fromCoordinate("github.com/org/repo");

        Optional<TaskRun> opt = service.findByProjectAndTask(projectId, "TASK-1");
        assertThat(opt).isEmpty();

        List<TaskRun> candidates = service.findActiveCandidates(projectId);
        assertThat(candidates).isEmpty();

        long count = service.countByProject("github.com/org/repo");
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Given StepAuditEntity and MetricLogEntity - verifies properties and sanitization sinks")
    void testEntityPropertyAccess() {
        StepAuditEntity step = new StepAuditEntity();
        step.setSanitizedInput(null);
        step.setOutputSummary(null);
        assertThat(step.sanitizedInput).isNull();
        assertThat(step.outputSummary).isNull();

        org.quarkus.agent.workspace.SecretRedactionFilter filter = new org.quarkus.agent.workspace.SecretRedactionFilter();
        step.setSanitizedInput(filter.sanitize("git push password=mySecret123"));
        step.setOutputSummary(filter.sanitize("Auth failure"));

        assertThat(step.sanitizedInput).contains("***REDACTED_CREDENTIAL***");
        assertThat(step.outputSummary).isEqualTo("Auth failure");

        MetricLogEntity metric = new MetricLogEntity();
        metric.id = 1L;
        metric.taskRunId = 10L;
        metric.phaseName = "PLANNING";
        metric.modelName = "gemini-2.5-flash";
        metric.promptTokens = 100;
        metric.completionTokens = 50;
        metric.costUsd = java.math.BigDecimal.valueOf(0.001);
        metric.cacheHit = true;
        metric.retrievedAdrs = "ADR-001";

        assertThat(metric.id).isEqualTo(1L);
        assertThat(metric.phaseName).isEqualTo("PLANNING");
        assertThat(metric.costUsd).isEqualByComparingTo("0.001");
    }

    @Test
    @DisplayName("Given active Panache queries - findByProjectAndTask returns mapped entity")
    void testPanacheQueryMethods() {
        TaskRunEntity entity = new TaskRunEntity();
        entity.id = 1L;
        entity.projectId = "org/repo";
        entity.taskId = "TASK-1";
        entity.status = TaskStatus.PLANNING;
        entity.currentPhase = "PLANNING";
        entity.createdAt = Instant.now();
        entity.updatedAt = Instant.now();

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-1"))
                .thenReturn(entity);

            ProjectId projectId = ProjectId.fromCoordinate("github.com/org/repo");
            assertThat(service.findByProjectAndTask("org/repo", "TASK-1")).isPresent();
            assertThat(service.findByProjectAndTask(projectId, "TASK-1")).isEmpty(); // different coordinate
        }
    }

    @Test
    @DisplayName("Given recordStateChange with new and existing entities - persists changes and audit steps")
    void testRecordStateChange() {
        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        TaskStateChangeEvent event = TaskStateChangeEvent.builder()
            .projectId("org/repo")
            .taskId("TASK-1")
            .newStatus(TaskStatus.RED_TEST)
            .phaseName("RED_TEST")
            .stepSummary("Red test phase started")
            .timestamp(now)
            .build();

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            // Case 1: Entity does not exist yet (new entity)
            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-1"))
                .thenReturn(null);

            SanitizedOutput summary = new SanitizedOutput("Sanitized summary text");
            TaskRun created = service.recordStateChange(event, summary);

            assertThat(created).isNotNull();
            assertThat(created.projectId()).isEqualTo("org/repo");
            assertThat(created.taskId()).isEqualTo("TASK-1");
            assertThat(created.status()).isEqualTo(TaskStatus.RED_TEST);
            assertThat(created.currentPhase()).isEqualTo("RED_TEST");
            assertThat(created.steps()).hasSize(1);
            assertThat(created.steps().getFirst().outputSummary()).isEqualTo("Sanitized summary text");
        }

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            // Case 2: Entity already exists with null steps and blank summary
            TaskRunEntity existing = new TaskRunEntity();
            existing.id = 2L;
            existing.projectId = "org/repo";
            existing.taskId = "TASK-1";
            existing.status = TaskStatus.PLANNING;
            existing.currentPhase = "PLANNING";
            existing.createdAt = now;
            existing.updatedAt = now;
            existing.steps = null;

            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-1"))
                .thenReturn(existing);

            TaskRun updated = service.recordStateChange(event, new SanitizedOutput("   "));
            assertThat(updated).isNotNull();
            assertThat(updated.id()).isEqualTo(2L);
            assertThat(updated.steps()).isEmpty();
        }

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            // Case 3: Entity already exists with null steps and valid summary
            TaskRunEntity existing = new TaskRunEntity();
            existing.id = 3L;
            existing.projectId = "org/repo";
            existing.taskId = "TASK-1";
            existing.steps = null;

            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-1"))
                .thenReturn(existing);

            TaskRun updated = service.recordStateChange(event, new SanitizedOutput("Valid step summary"));
            assertThat(updated).isNotNull();
            assertThat(updated.steps()).hasSize(1);
        }
    }

    @Test
    @DisplayName("Given saveTask with new and existing domain objects - handles timestamps and persists")
    void testSaveTask() {
        Instant now = Instant.parse("2026-10-25T14:00:00Z");
        TaskRun domainWithTimestamps = TaskRun.builder()
            .projectId("org/repo")
            .taskId("TASK-2")
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN")
            .branch("feature/TASK-2")
            .baseCommit("abc1234")
            .checkpointStash("stash@{0}")
            .pid(9999L)
            .retryCount(2)
            .createdAt(now)
            .updatedAt(now)
            .userStory("Story")
            .approvedPlanJson("{\"plan\":true}")
            .build();

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            // Case 1: Entity does not exist yet (create new)
            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-2"))
                .thenReturn(null);

            TaskRun saved = service.saveTask(domainWithTimestamps);
            assertThat(saved).isNotNull();
            assertThat(saved.projectId()).isEqualTo("org/repo");
            assertThat(saved.taskId()).isEqualTo("TASK-2");
            assertThat(saved.status()).isEqualTo(TaskStatus.GREEN_PATCH);
            assertThat(saved.branch()).isEqualTo("feature/TASK-2");

            // Also test null createdAt triggers Instant.now()
            TaskRun domainWithoutCreated = domainWithTimestamps.toBuilder().createdAt(null).build();
            TaskRun savedWithoutCreated = service.saveTask(domainWithoutCreated);
            assertThat(savedWithoutCreated.createdAt()).isNotNull();
        }

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            // Case 2: Existing entity without timestamps provided in domain
            TaskRunEntity existing = new TaskRunEntity();
            existing.id = 15L;
            existing.projectId = "org/repo";
            existing.taskId = "TASK-2";
            existing.createdAt = now;

            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-2"))
                .thenReturn(existing);

            TaskRun domainWithoutTimestamps = TaskRun.builder()
                .projectId("org/repo")
                .taskId("TASK-2")
                .status(TaskStatus.COMPLETED)
                .currentPhase("COMPLETED")
                .build();

            TaskRun savedExisting = service.saveTask(domainWithoutTimestamps);
            assertThat(savedExisting).isNotNull();
            assertThat(savedExisting.id()).isEqualTo(15L);
            assertThat(savedExisting.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(savedExisting.createdAt()).isNotNull();
            assertThat(savedExisting.updatedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("Given deleteAll - safely invokes cleanup without throwing")
    void testDeleteAll() {
        service.deleteAll();
    }

    @Test
    @DisplayName("Given persistEntity - handles successful persistence and defensive exception catches")
    void testPersistEntity() {
        TaskRunEntity mockEntity = org.mockito.Mockito.mock(TaskRunEntity.class);
        service.persistEntity(mockEntity);
        org.mockito.Mockito.verify(mockEntity).persist();

        TaskRunEntity throwingEntity = org.mockito.Mockito.mock(TaskRunEntity.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Panache not ready")).when(throwingEntity).persist();
        service.persistEntity(throwingEntity);
    }

    @Test
    @DisplayName("Given uninitialized entity findByProjectAndTask - recordStateChange and saveTask fall back cleanly")
    void testRecordStateChangeAndSaveTaskPanacheExceptions() {
        TaskStateChangeEvent event = TaskStateChangeEvent.builder()
            .projectId("org/repo")
            .taskId("TASK-EX")
            .newStatus(TaskStatus.RED_TEST)
            .phaseName("RED_TEST")
            .build();

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-EX"))
                .thenThrow(new IllegalStateException("Uninitialized Arc"));

            TaskRun run = service.recordStateChange(event, null);
            assertThat(run).isNotNull();
            assertThat(run.taskId()).isEqualTo("TASK-EX");
        }

        try (MockedStatic<TaskRunEntity> mockedTask = mockStatic(TaskRunEntity.class)) {
            mockedTask.when(() -> TaskRunEntity.findByProjectAndTask("org/repo", "TASK-EX"))
                .thenThrow(new IllegalStateException("Uninitialized Arc"));

            TaskRun taskToSave = TaskRun.builder().projectId("org/repo").taskId("TASK-EX").build();
            TaskRun saved = service.saveTask(taskToSave);
            assertThat(saved).isNotNull();
            assertThat(saved.taskId()).isEqualTo("TASK-EX");
        }
    }
}
