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
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.StepAudit;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStateChangeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;

/**
 * Service that synchronizes database transitions to human-readable Markdown run journals.
 * Consumes decoupled domain models and delegates entity operations to TaskPersistenceService.
 */
@ApplicationScoped
public class StateSyncService {

    private static final Logger LOG = Logger.getLogger(StateSyncService.class);

    private final WorkspaceContextResolver workspaceResolver;
    private final SecretRedactionFilter secretFilter;
    private final TaskPersistenceService persistenceService;

    @Inject
    Instance<TransactionSynchronizationRegistry> registryInstance;

    @Inject
    public StateSyncService(
        WorkspaceContextResolver workspaceResolver,
        SecretRedactionFilter secretFilter,
        TaskPersistenceService persistenceService
    ) {
        this.workspaceResolver = workspaceResolver;
        this.secretFilter = secretFilter;
        this.persistenceService = persistenceService;
    }

    public StateSyncService() {
        this(new WorkspaceContextResolver(), new SecretRedactionFilter(), new TaskPersistenceService());
    }

    @Transactional
    public void onStateTransition(@Observes TaskStateChangeEvent event) {
        SanitizedOutput sanitizedSummary = event.stepSummary() != null && !event.stepSummary().isBlank()
            ? secretFilter.sanitize(event.stepSummary())
            : null;

        TaskRun taskRun = persistenceService.recordStateChange(event, sanitizedSummary);
        if (taskRun != null) {
            scheduleMarkdownProjection(taskRun);
        }
    }

    private void scheduleMarkdownProjection(TaskRun taskRun) {
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
                                projectToMarkdown(taskRun);
                            }
                        }
                    });
                    return;
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to register transaction synchronization: %s", e.getMessage());
            }
        }
        projectToMarkdown(taskRun);
    }

    public void projectToMarkdown(TaskRun taskRun) {
        if (taskRun.taskId() == null || taskRun.taskId().contains("..") || taskRun.taskId().contains("/") || taskRun.taskId().contains("\\")) {
            throw new SecurityException("Jailbreak attempt: Task ID resolves outside the runs directory: " + taskRun.taskId());
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
            taskRun.taskId(),
            taskRun.projectId(),
            taskRun.status(),
            taskRun.currentPhase(),
            taskRun.branch() != null ? taskRun.branch() : "unassigned",
            taskRun.baseCommit() != null ? taskRun.baseCommit() : "HEAD",
            taskRun.checkpointStash() != null ? taskRun.checkpointStash() : "none",
            taskRun.pid() != null ? taskRun.pid() : 0L,
            taskRun.retryCount(),
            taskRun.updatedAt() != null ? DateTimeFormatter.ISO_INSTANT.format(taskRun.updatedAt()) : "unknown",
            taskRun.taskId(),
            taskRun.userStory() != null ? taskRun.userStory() : "None specified",
            taskRun.approvedPlanJson() != null ? taskRun.approvedPlanJson() : "{}",
            renderStepsHistory(taskRun)
        );

        try {
            Files.createDirectories(targetRunsDir);
            RepoPath runsDir = RepoPath.of(targetRunsDir, workspaceResolver.resolveRepoRoot());
            RepoPath journalFile = runsDir.resolve(taskRun.taskId() + ".md");

            Files.writeString(
                journalFile.toPath(),
                secretFilter.sanitize(markdown).value(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            LOG.warnf(e, "Failed to project Markdown state journal: %s", e.getMessage());
        }
    }

    private String renderStepsHistory(TaskRun taskRun) {
        if (taskRun.steps() == null || taskRun.steps().isEmpty()) {
            return "- No steps executed yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (StepAudit step : taskRun.steps()) {
            sb.append(String.format("- [%s] **%s** (%dms): %s%n",
                step.exitCode() == 0 ? "x" : " ",
                step.toolName(),
                step.durationMs(),
                step.outputSummary() != null ? step.outputSummary() : ""
            ));
        }
        return sb.toString();
    }
}
