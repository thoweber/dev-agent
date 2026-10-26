package org.quarkus.agent.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.quarkus.agent.model.TaskStatus;

/**
 * Panache entity representing the lifecycle and persistence of an engineering task run.
 */
@Entity
@Table(name = "task_runs", indexes = {
    @Index(name = "idx_project_task", columnList = "project_id, task_id", unique = true),
    @Index(name = "idx_status", columnList = "status")
})
@RegisterForReflection
public class TaskRunEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "project_id", nullable = false)
    public String projectId;

    @Column(name = "task_id", nullable = false)
    public String taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public TaskStatus status;

    @Column(name = "current_phase", nullable = false)
    public String currentPhase;

    @Column(name = "branch_name")
    public String branchName;

    @Column(name = "base_commit")
    public String baseCommit;

    @Column(name = "checkpoint_stash")
    public String checkpointStash;

    @Column(name = "approved_plan_json", columnDefinition = "TEXT")
    public String approvedPlanJson;

    @Column(name = "user_story", columnDefinition = "TEXT")
    public String userStory;

    public Long pid;

    @Column(name = "retry_count")
    public int retryCount;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @OneToMany(mappedBy = "taskRun", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<StepAuditEntity> steps = new ArrayList<>();

    public static TaskRunEntity findByProjectAndTask(String projectId, String taskId) {
        return find("projectId = ?1 and taskId = ?2", projectId, taskId).firstResult();
    }
}
