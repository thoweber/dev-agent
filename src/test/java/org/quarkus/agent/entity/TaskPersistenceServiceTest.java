package org.quarkus.agent.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
}
