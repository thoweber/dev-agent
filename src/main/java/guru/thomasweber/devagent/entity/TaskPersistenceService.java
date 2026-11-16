package guru.thomasweber.devagent.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import guru.thomasweber.devagent.model.ProjectId;
import guru.thomasweber.devagent.model.StepAudit;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStateChangeEvent;
import guru.thomasweber.devagent.workspace.SanitizedOutput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Encapsulated persistence adapter and mapping sink for TaskRun and StepAudit entities.
 * Strictly prevents Panache entities from leaking into external domain and workspace services.
 */
@ApplicationScoped
@RegisterForReflection
public class TaskPersistenceService {

    private static final Logger LOG = Logger.getLogger(TaskPersistenceService.class);

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
            List<TaskRunEntity> candidates = TaskRunEntity.findActiveByProject(projectId);
            return candidates.stream().map(this::toDomainSummary).toList();
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
        if (isNull(entity)) {
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

        if (nonNull(sanitizedSummary) && !sanitizedSummary.isBlank()) {
            StepAuditEntity step = new StepAuditEntity();
            step.taskRun = entity;
            step.toolName = event.phaseName();
            step.setOutputSummary(sanitizedSummary);
            step.exitCode = 0;
            step.durationMs = 0L;
            step.createdAt = event.timestamp();
            persistEntity(step);
            if (isNull(entity.steps)) {
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
        if (isNull(entity)) {
            entity = new TaskRunEntity();
            entity.projectId = taskRun.projectId();
            entity.taskId = taskRun.taskId();
            entity.createdAt = Objects.requireNonNullElseGet(taskRun.createdAt(), Instant::now);
        }
        entity.status = taskRun.status();
        entity.currentPhase = taskRun.currentPhase();
        entity.branchName = taskRun.branch();
        entity.baseCommit = taskRun.baseCommit();
        entity.checkpointStash = taskRun.checkpointStash();
        entity.pid = taskRun.pid();
        entity.retryCount = taskRun.retryCount();
        entity.updatedAt = Objects.requireNonNullElseGet(taskRun.updatedAt(), Instant::now);
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

    void persistEntity(PanacheEntityBase entity) {
        try {
            entity.persist();
        } catch (IllegalStateException | NullPointerException e) {
            LOG.debugf(e, "Panache runtime uninitialized during persist: %s", e.getMessage());
        }
    }

    public TaskRun toDomain(TaskRunEntity entity) {
        if (isNull(entity)) {
            return null;
        }
        List<StepAudit> stepAudits = new ArrayList<>();
        if (nonNull(entity.steps)) {
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
        return toDomain(entity, stepAudits);
    }

    private TaskRun toDomainSummary(TaskRunEntity entity) {
        return toDomain(entity, List.of());
    }

    private TaskRun toDomain(TaskRunEntity entity, List<StepAudit> stepAudits) {
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
