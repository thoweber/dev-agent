package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.quarkus.agent.entity.StepAuditEntity;
import org.quarkus.agent.entity.TaskRunEntity;
import org.quarkus.agent.model.TaskStateChangeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;

/**
 * Service that synchronizes database transitions to human-readable Markdown run journals.
 */
@ApplicationScoped
public class StateSyncService {

    @Inject
    WorkspaceContextResolver workspaceResolver;

    @Inject
    SecretRedactionFilter secretFilter;

    @Transactional
    public void onStateTransition(@Observes TaskStateChangeEvent event) {
        TaskRunEntity entity = TaskRunEntity.findByProjectAndTask(event.projectId(), event.taskId());
        if (entity == null) {
            return;
        }

        entity.status = event.newStatus();
        entity.currentPhase = event.phaseName();
        entity.updatedAt = event.timestamp();
        entity.persist();

        projectToMarkdown(entity);
    }

    public void projectToMarkdown(TaskRunEntity entity) {
        Path targetRunsDir = workspaceResolver.getProjectRoot().resolve(".agents").resolve("runs");

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
            Path journalFile = targetRunsDir.resolve(entity.taskId + ".md");
            Files.writeString(
                journalFile,
                secretFilter.scrub(markdown),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            System.err.println("WARN: Failed to project Markdown state journal: " + e.getMessage());
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
