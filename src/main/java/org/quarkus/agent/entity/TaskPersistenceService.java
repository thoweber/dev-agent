package org.quarkus.agent.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.quarkus.agent.model.ProjectId;
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

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(TaskPersistenceService.class);

    @Transactional
    public Optional<TaskRun> findByProjectAndTask(String projectId, String taskId) {
        try {
            TaskRunEntity entity = TaskRunEntity.findByProjectAndTask(projectId, taskId);
            return Optional.ofNullable(entity).map(this::toDomain);
        } catch (IllegalStateException | NullPointerException _) {
            return Optional.empty();
        }
    }

    @Transactional
    public Optional<TaskRun> findByProjectAndTask(ProjectId projectId, String taskId) {
        return findByProjectAndTask(projectId.idString(), taskId);
    }

    @Transactional
    public List<TaskRun> findActiveCandidates(String projectId) {
        try {
            List<TaskRunEntity> candidates = TaskRunEntity.list(
                "projectId = ?1 and status not in (?2, ?3, ?4) order by updatedAt desc",
                projectId, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED
            );
            return candidates.stream().map(this::toDomain).toList();
        } catch (IllegalStateException | NullPointerException _) {
            return List.of();
        }
    }

    @Transactional
    public List<TaskRun> findActiveCandidates(ProjectId projectId) {
        return findActiveCandidates(projectId.idString());
    }

    @Transactional
    public long countByProject(String projectId) {
        try {
            return TaskRunEntity.count("projectId", projectId);
        } catch (IllegalStateException | NullPointerException _) {
            return 0L;
        }
    }

    @Transactional
    public long countByProject(ProjectId projectId) {
        return countByProject(projectId.idString());
    }

    @Transactional
    public TaskRun recordStateChange(TaskStateChangeEvent event, SanitizedOutput sanitizedSummary) {
        TaskRunEntity entity = null;
        try {
            entity = TaskRunEntity.findByProjectAndTask(event.projectId(), event.taskId());
        } catch (IllegalStateException | NullPointerException e) {
            LOG.debugf(e, "Uninitialized persistence runtime fallback during recordStateChange: %s", e.getMessage());
        }
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

        persistEntity(entity);

        if (sanitizedSummary != null && !sanitizedSummary.isBlank()) {
            StepAuditEntity step = new StepAuditEntity();
            step.taskRun = entity;
            step.toolName = event.phaseName();
            step.setOutputSummary(sanitizedSummary);
            step.exitCode = 0;
            step.durationMs = 0L;
            step.createdAt = event.timestamp();
            persistEntity(step);
            if (entity.steps == null) {
                entity.steps = new ArrayList<>();
            }
            entity.steps.add(step);
        }

        return toDomain(entity);
    }

    @Transactional
    public TaskRun saveTask(TaskRun taskRun) {
        TaskRunEntity entity = null;
        try {
            entity = TaskRunEntity.findByProjectAndTask(taskRun.projectId(), taskRun.taskId());
        } catch (IllegalStateException | NullPointerException e) {
            LOG.debugf(e, "Uninitialized persistence runtime fallback during saveTask: %s", e.getMessage());
        }
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
        persistEntity(entity);
        return toDomain(entity);
    }

    @Transactional
    public void deleteAll() {
        StepAuditEntity.deleteAll();
        TaskRunEntity.deleteAll();
    }

    void persistEntity(io.quarkus.hibernate.orm.panache.PanacheEntityBase entity) {
        try {
            entity.persist();
        } catch (IllegalStateException | NullPointerException e) {
            LOG.debugf(e, "Panache runtime uninitialized during persist: %s", e.getMessage());
        }
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
