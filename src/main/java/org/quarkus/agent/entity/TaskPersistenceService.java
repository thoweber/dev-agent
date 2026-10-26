package org.quarkus.agent.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.quarkus.agent.model.StepAudit;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStateChangeEvent;
import org.quarkus.agent.model.TaskStatus;
import org.quarkus.agent.workspace.SanitizedOutput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Encapsulated persistence adapter and mapping sink for TaskRun and StepAudit entities.
 * Strictly prevents Panache entities from leaking into external domain and workspace services.
 */
@ApplicationScoped
@RegisterForReflection
public class TaskPersistenceService {

    public Optional<TaskRun> findByProjectAndTask(String projectId, String taskId) {
        TaskRunEntity entity = TaskRunEntity.findByProjectAndTask(projectId, taskId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    public List<TaskRun> findActiveCandidates(String projectId) {
        List<TaskRunEntity> candidates = TaskRunEntity.list(
            "projectId = ?1 and status not in (?2, ?3, ?4) order by updatedAt desc",
            projectId, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED
        );
        return candidates.stream().map(this::toDomain).toList();
    }

    public long countByProject(String projectId) {
        return TaskRunEntity.count("projectId", projectId);
    }

    @Transactional
    public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
        TaskRunEntity entity = TaskRunEntity.findByProjectAndTask(event.projectId(), event.taskId());
        if (entity == null) {
            entity = new TaskRunEntity();
            entity.projectId = event.projectId();
            entity.taskId = event.taskId();
            entity.status = event.newStatus();
            entity.currentPhase = event.phaseName();
            entity.createdAt = event.timestamp();
            entity.updatedAt = event.timestamp();
            entity.steps = new ArrayList<>();
        } else {
            entity.status = event.newStatus();
            entity.currentPhase = event.phaseName();
            entity.updatedAt = event.timestamp();
        }

        if (sanitizedSummary != null && !sanitizedSummary.isBlank()) {
            StepAuditEntity step = new StepAuditEntity();
            step.taskRun = entity;
            step.toolName = event.phaseName();
            step.setOutputSummary(sanitizedSummary);
            step.exitCode = 0;
            step.durationMs = 0L;
            step.createdAt = event.timestamp();
            step.persist();
            if (entity.steps == null) {
                entity.steps = new ArrayList<>();
            }
            entity.steps.add(step);
        }

        entity.persist();
        return toDomain(entity);
    }

    @Transactional
    public TaskRun saveTask(TaskRun taskRun) {
        TaskRunEntity entity = TaskRunEntity.findByProjectAndTask(taskRun.projectId(), taskRun.taskId());
        if (entity == null) {
            entity = new TaskRunEntity();
            entity.projectId = taskRun.projectId();
            entity.taskId = taskRun.taskId();
            entity.createdAt = taskRun.createdAt() != null ? taskRun.createdAt() : Instant.now();
        }
        entity.status = taskRun.status();
        entity.currentPhase = taskRun.currentPhase();
        entity.branchName = taskRun.branch();
        entity.baseCommit = taskRun.baseCommit();
        entity.checkpointStash = taskRun.checkpointStash();
        entity.pid = taskRun.pid();
        entity.retryCount = taskRun.retryCount();
        entity.updatedAt = taskRun.updatedAt() != null ? taskRun.updatedAt() : Instant.now();
        entity.userStory = taskRun.userStory();
        entity.approvedPlanJson = taskRun.approvedPlanJson();
        entity.persist();
        return toDomain(entity);
    }

    @Transactional
    public void deleteAll() {
        StepAuditEntity.deleteAll();
        TaskRunEntity.deleteAll();
    }

    public TaskRun toDomain(TaskRunEntity entity) {
        if (entity == null) {
            return null;
        }
        List<StepAudit> stepAudits = new ArrayList<>();
        if (entity.steps != null) {
            for (StepAuditEntity stepEntity : entity.steps) {
                stepAudits.add(StepAudit.builder()
                    .id(stepEntity.id)
                    .toolName(stepEntity.toolName)
                    .sanitizedInput(stepEntity.sanitizedInput)
                    .outputSummary(stepEntity.outputSummary)
                    .durationMs(stepEntity.durationMs)
                    .exitCode(stepEntity.exitCode)
                    .createdAt(stepEntity.createdAt)
                    .build());
            }
        }
        return TaskRun.builder()
            .id(entity.id)
            .projectId(entity.projectId)
            .taskId(entity.taskId)
            .status(entity.status)
            .currentPhase(entity.currentPhase)
            .branch(entity.branchName)
            .baseCommit(entity.baseCommit)
            .checkpointStash(entity.checkpointStash)
            .pid(entity.pid)
            .retryCount(entity.retryCount)
            .createdAt(entity.createdAt)
            .updatedAt(entity.updatedAt)
            .userStory(entity.userStory)
            .approvedPlanJson(entity.approvedPlanJson)
            .steps(stepAudits)
            .build();
    }
}
