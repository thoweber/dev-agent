package org.quarkus.agent.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Granular telemetry record tracking token consumption, model invocations, and cost estimations.
 */
@Entity
@Table(name = "metric_logs")
@RegisterForReflection
public class MetricLogEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "task_run_id", nullable = false)
    public Long taskRunId;

    @Column(name = "phase_name", nullable = false)
    public String phaseName;

    @Column(name = "model_name", nullable = false)
    public String modelName;

    @Column(name = "prompt_tokens")
    public int promptTokens;

    @Column(name = "completion_tokens")
    public int completionTokens;

    @Column(name = "cost_usd")
    public BigDecimal costUsd;

    @Column(name = "cache_hit")
    public boolean cacheHit;

    @Column(name = "retrieved_adrs")
    public String retrievedAdrs;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
