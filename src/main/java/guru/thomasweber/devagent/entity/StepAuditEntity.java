package guru.thomasweber.devagent.entity;

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
import guru.thomasweber.devagent.workspace.SanitizedOutput;

import static java.util.Objects.isNull;

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

    public void setSanitizedInput(SanitizedOutput input) {
        if (isNull(input)) {
            this.sanitizedInput = null;
            return;
        }
        this.sanitizedInput = input.value();
    }

    public void setOutputSummary(SanitizedOutput summary) {
        if (isNull(summary)) {
            this.outputSummary = null;
            return;
        }
        this.outputSummary = summary.value();
    }
}
