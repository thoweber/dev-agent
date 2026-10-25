package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.quarkus.agent.entity.StepAuditEntity;
import org.quarkus.agent.entity.TaskRunEntity;
import org.quarkus.agent.model.TaskStateChangeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/**
 * Service that synchronizes database transitions to human-readable Markdown run journals.
 */
@ApplicationScoped
public class StateSyncService {

    private static final Logger LOG = Logger.getLogger(StateSyncService.class);

    private final WorkspaceContextResolver workspaceResolver;
    private final SecretRedactionFilter secretFilter;

    @Inject
    Instance<TransactionSynchronizationRegistry> registryInstance;

    @Inject
    public StateSyncService(WorkspaceContextResolver workspaceResolver, SecretRedactionFilter secretFilter) {
        this.workspaceResolver = workspaceResolver;
        this.secretFilter = secretFilter;
    }

    public StateSyncService() {
        this(new WorkspaceContextResolver(), new SecretRedactionFilter());
    }

    @Transactional
    public void onStateTransition(@Observes TaskStateChangeEvent event) {
        TaskRunEntity entity = TaskRunEntity.findByProjectAndTask(event.projectId(), event.taskId());
        if (entity == null) {
            return;
        }

        entity.status = event.newStatus();
        entity.currentPhase = event.phaseName();
        entity.updatedAt = event.timestamp();

        if (event.stepSummary() != null && !event.stepSummary().isBlank()) {
            StepAuditEntity step = new StepAuditEntity();
            step.taskRun = entity;
            step.toolName = event.phaseName();
            step.outputSummary = secretFilter.scrub(event.stepSummary());
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

        scheduleMarkdownProjection(entity);
    }

    private void scheduleMarkdownProjection(TaskRunEntity entity) {
        if (registryInstance != null && registryInstance.isResolvable()) {
            try {
                TransactionSynchronizationRegistry registry = registryInstance.get();
                if (registry.getTransactionStatus() == Status.STATUS_ACTIVE) {
                    registry.registerInterposedSynchronization(new Synchronization() {
                        @Override
                        public void beforeCompletion() {
                            // No-op
                        }

                        @Override
                        public void afterCompletion(int status) {
                            if (status == Status.STATUS_COMMITTED) {
                                projectToMarkdown(entity);
                            }
                        }
                    });
                    return;
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to register transaction synchronization: %s", e.getMessage());
            }
        }
        projectToMarkdown(entity);
    }

    public void projectToMarkdown(TaskRunEntity entity) {
        if (entity.taskId == null || entity.taskId.contains("..") || entity.taskId.contains("/") || entity.taskId.contains("\\")) {
            throw new SecurityException("Jailbreak attempt: Task ID resolves outside the runs directory: " + entity.taskId);
        }

        Path targetRunsDir = workspaceResolver.resolveAgentsDir().resolve("runs");

        String markdown = String.format("""
            ---
            task_id: "%s"
            project_id: "%s"
            status: "%s"
            current_phase: "%s"
            branch: "%s"
            base_commit: "%s"
            checkpoint_stash: "%s"
            pid: %d
            retry_count: %d
            updated_at: "%s"
            ---

            # Task Execution Journal: %s

            ## User Story
            %s

            ## Approved Implementation Plan
            ```json
            %s
            ```

            ## Step Execution History
            %s
            """,
            entity.taskId,
            entity.projectId,
            entity.status,
            entity.currentPhase,
            entity.branchName != null ? entity.branchName : "unassigned",
            entity.baseCommit != null ? entity.baseCommit : "HEAD",
            entity.checkpointStash != null ? entity.checkpointStash : "none",
            entity.pid != null ? entity.pid : 0L,
            entity.retryCount,
            entity.updatedAt != null ? DateTimeFormatter.ISO_INSTANT.format(entity.updatedAt) : "unknown",
            entity.taskId,
            entity.userStory != null ? entity.userStory : "None specified",
            entity.approvedPlanJson != null ? entity.approvedPlanJson : "{}",
            renderStepsHistory(entity)
        );

        try {
            Files.createDirectories(targetRunsDir);
            Path realRunsDir = targetRunsDir.toRealPath();
            Path normalizedRunsDir = targetRunsDir.toAbsolutePath().normalize();
            Path journalFile = normalizedRunsDir.resolve(entity.taskId + ".md").normalize();

            if (Files.isSymbolicLink(journalFile)) {
                throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for task journals: " + journalFile);
            }

            if (Files.exists(journalFile, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(journalFile) || !journalFile.toRealPath().startsWith(realRunsDir)) {
                    throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for task journals: " + journalFile);
                }
            }

            if (!journalFile.startsWith(normalizedRunsDir)) {
                throw new SecurityException("Jailbreak attempt: Task ID resolves outside the runs directory: " + entity.taskId);
            }

            Files.writeString(
                journalFile,
                secretFilter.scrub(markdown),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            LOG.warnf(e, "Failed to project Markdown state journal: %s", e.getMessage());
        }
    }

    private String renderStepsHistory(TaskRunEntity entity) {
        if (entity.steps == null || entity.steps.isEmpty()) {
            return "- No steps executed yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (StepAuditEntity step : entity.steps) {
            sb.append(String.format("- [%s] **%s** (%dms): %s%n",
                step.exitCode == 0 ? "x" : " ",
                step.toolName,
                step.durationMs,
                step.outputSummary != null ? step.outputSummary : ""
            ));
        }
        return sb.toString();
    }
}
