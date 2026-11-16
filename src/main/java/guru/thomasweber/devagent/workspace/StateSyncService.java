package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.cognitive.MarkdownParserService;
import guru.thomasweber.devagent.cognitive.MarkdownParserService.ParsedMarkdown;
import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.StepAudit;
import guru.thomasweber.devagent.model.TaskId;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStateChangeEvent;
import guru.thomasweber.devagent.model.WorktreeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

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
    private final MarkdownParserService markdownParserService;
    private final Instance<TransactionSynchronizationRegistry> registryInstance;
    private final StateSocketService stateSocketService;

    @Inject
    public StateSyncService(
        WorkspaceContextResolver workspaceResolver,
        SecretRedactionFilter secretFilter,
        TaskPersistenceService persistenceService,
        MarkdownParserService markdownParserService,
        Instance<TransactionSynchronizationRegistry> registryInstance,
        StateSocketService stateSocketService
    ) {
        this.workspaceResolver = workspaceResolver;
        this.secretFilter = secretFilter;
        this.persistenceService = persistenceService;
        this.markdownParserService = markdownParserService;
        this.registryInstance = registryInstance;
        this.stateSocketService = stateSocketService;
    }

    public StateSyncService(
        WorkspaceContextResolver workspaceResolver,
        SecretRedactionFilter secretFilter,
        TaskPersistenceService persistenceService,
        MarkdownParserService markdownParserService,
        Instance<TransactionSynchronizationRegistry> registryInstance
    ) {
        this(workspaceResolver, secretFilter, persistenceService, markdownParserService, registryInstance, new StateSocketService(secretFilter, workspaceResolver));
    }

    public StateSyncService(
        WorkspaceContextResolver workspaceResolver,
        SecretRedactionFilter secretFilter,
        TaskPersistenceService persistenceService,
        Instance<TransactionSynchronizationRegistry> registryInstance
    ) {
        this(workspaceResolver, secretFilter, persistenceService, new MarkdownParserService(), registryInstance, new StateSocketService(secretFilter, workspaceResolver));
    }

    public StateSyncService(
        WorkspaceContextResolver workspaceResolver,
        SecretRedactionFilter secretFilter,
        TaskPersistenceService persistenceService
    ) {
        this(workspaceResolver, secretFilter, persistenceService, new MarkdownParserService(), null, new StateSocketService(secretFilter, workspaceResolver));
    }

    public StateSyncService() {
        this(new WorkspaceContextResolver(), new SecretRedactionFilter(), new TaskPersistenceService(), new MarkdownParserService(), null, new StateSocketService());
    }

    @Transactional
    public void onStateTransition(@Observes TaskStateChangeEvent event) {
        SanitizedOutput sanitizedSummary = null;
        if (nonNull(event.stepSummary()) && !event.stepSummary().isBlank()) {
            sanitizedSummary = secretFilter.sanitize(event.stepSummary());
        }

        TaskRun taskRun = persistenceService.recordStateChange(event, sanitizedSummary);
        if (nonNull(taskRun)) {
            scheduleMarkdownProjection(taskRun);
        }
    }

    private void scheduleMarkdownProjection(TaskRun taskRun) {
        if (nonNull(registryInstance) && registryInstance.isResolvable()) {
            try {
                TransactionSynchronizationRegistry registry = registryInstance.get();
                if (registry.getTransactionStatus() == Status.STATUS_ACTIVE) {
                    registerPostCommitProjection(registry, taskRun);
                    return;
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to register transaction synchronization: %s", e.getMessage());
            }
        }
        projectToMarkdown(taskRun);
    }

    private void registerPostCommitProjection(TransactionSynchronizationRegistry registry, TaskRun taskRun) {
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
    }

    public void projectToMarkdown(TaskRun taskRun) {
        TaskId safeTaskId = TaskId.of(taskRun.taskId());

        String formattedUpdatedAt = "unknown";
        if (nonNull(taskRun.updatedAt())) {
            formattedUpdatedAt = DateTimeFormatter.ISO_INSTANT.format(taskRun.updatedAt());
        }
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
            Objects.requireNonNullElse(taskRun.branch(), "unassigned"),
            Objects.requireNonNullElse(taskRun.baseCommit(), "HEAD"),
            Objects.requireNonNullElse(taskRun.checkpointStash(), "none"),
            Objects.requireNonNullElse(taskRun.pid(), 0L),
            taskRun.retryCount(),
            formattedUpdatedAt,
            taskRun.taskId(),
            Objects.requireNonNullElse(taskRun.userStory(), "None specified"),
            Objects.requireNonNullElse(taskRun.approvedPlanJson(), "{}"),
            renderStepsHistory(taskRun)
        );

        try {
            Path repoRoot = workspaceResolver.resolveRepoRoot().toRealPath();
            Path targetRunsDir = repoRoot.resolve(".agents/runs");
            RepoPath runsDir = RepoPath.of(targetRunsDir, repoRoot);
            Files.createDirectories(runsDir.toPath());
            RepoPath journalFile = runsDir.resolve(safeTaskId.value() + ".md");

            Files.writeString(
                journalFile.toPath(),
                secretFilter.sanitize(markdown).value(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            );
            mirrorStateSocketIfWorktreeExists(repoRoot, taskRun);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to project Markdown state journal: %s", e.getMessage());
        }
    }

    private void mirrorStateSocketIfWorktreeExists(Path repoRoot, TaskRun taskRun) {
        if (isNull(stateSocketService)) {
            return;
        }
        TaskId taskId = TaskId.of(taskRun.taskId());
        Path worktreeDir = WorktreeLayout.forRoot(repoRoot).worktreeDir(taskId);
        if (!Files.isDirectory(worktreeDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Optional<WorktreeState> existing = stateSocketService.readState(repoRoot, taskId);
        WorktreeState updated = WorktreeState.builder()
            .taskId(taskId.value())
            .projectId(taskRun.projectId())
            .status(taskRun.status())
            .phase(taskRun.currentPhase())
            .tokens(existing.map(WorktreeState::tokens).orElse(0L))
            .cost(existing.map(WorktreeState::cost).orElse(null))
            .pid(Objects.requireNonNullElseGet(taskRun.pid(), () -> existing.map(WorktreeState::pid).orElse(null)))
            .branch(Objects.requireNonNullElseGet(taskRun.branch(), () -> existing.map(WorktreeState::branch).orElse(null)))
            .worktreePath(worktreeDir.toString())
            .startedAt(existing.map(WorktreeState::startedAt).orElse(taskRun.createdAt()))
            .updatedAt(taskRun.updatedAt())
            .lastMessage(existing.map(WorktreeState::lastMessage).orElse(taskRun.currentPhase()))
            .build();
        stateSocketService.writeHeartbeat(repoRoot, updated);
    }

    /**
     * Reads and parses a persisted task execution journal using MarkdownParserService.
     *
     * @param taskId the task ID to read
     * @return parsed markdown containing YAML frontmatter and structural sections, or empty if not found
     */
    public Optional<ParsedMarkdown> readTaskJournal(String taskId) {
        TaskId safeTaskId = TaskId.of(taskId);
        try {
            Path repoRoot = workspaceResolver.resolveRepoRoot().toRealPath();
            Path targetRunsDir = repoRoot.resolve(".agents/runs");
            RepoPath runsDir = RepoPath.of(targetRunsDir, repoRoot);
            RepoPath journalFile = runsDir.resolve(safeTaskId.value() + ".md");
            if (!Files.isRegularFile(journalFile.toPath())) {
                return Optional.empty();
            }
            String content = Files.readString(journalFile.toPath());
            return Optional.of(markdownParserService.parse(content));
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read Markdown state journal for taskId=%s: %s", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    private String renderStepsHistory(TaskRun taskRun) {
        if (isNull(taskRun.steps()) || taskRun.steps().isEmpty()) {
            return "- No steps executed yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (StepAudit step : taskRun.steps()) {
            String marker = " ";
            if (step.exitCode() == 0) {
                marker = "x";
            }
            sb.append(String.format("- [%s] **%s** (%dms): %s%n",
                marker,
                step.toolName(),
                step.durationMs(),
                Objects.toString(step.outputSummary(), "")
            ));
        }
        return sb.toString();
    }
}
