package org.quarkus.agent.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Audit record of individual tool executions and code patches.
 */
@Entity
@Table(name = "step_audits")
@RegisterForReflection
public class StepAuditEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_run_id", nullable = false)
    public TaskRunEntity taskRun;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    @Column(name = "sanitized_input", columnDefinition = "TEXT")
    public String sanitizedInput;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    public String outputSummary;

    @Column(name = "duration_ms")
    public long durationMs;

    @Column(name = "exit_code")
    public int exitCode;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
