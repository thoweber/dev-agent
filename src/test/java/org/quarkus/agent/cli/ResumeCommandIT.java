package org.quarkus.agent.cli;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;
import org.quarkus.agent.testutil.TaskRunMother;
import org.quarkus.agent.testutil.WorktreeTestFixture;
import org.quarkus.agent.workspace.CrashRecoveryDetector;
import org.quarkus.agent.workspace.ProjectIdResolver;
import org.quarkus.agent.workspace.WorkspaceContextResolver;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ResumeCommandIT {

    @Inject
    TaskPersistenceService persistenceService;

    @Inject
    CrashRecoveryDetector crashDetector;

    @Inject
    ProjectIdResolver projectResolver;

    @Inject
    WorkspaceContextResolver workspaceResolver;

    private Path repoRoot() {
        return workspaceResolver.resolveRepoRoot();
    }

    private void createWorktree(String taskId) throws IOException {
        WorktreeTestFixture.createWorktree(repoRoot(), taskId);
    }

    @BeforeEach
    void setUp() throws IOException {
        persistenceService.deleteAll();
        WorktreeTestFixture.cleanAllWorktrees(repoRoot());
    }

    @AfterEach
    void tearDown() throws IOException {
        WorktreeTestFixture.cleanAllWorktrees(repoRoot());
    }

    @Test
    @DisplayName("Given no interrupted tasks - when resume executed without arguments - reports no tasks found")
    void testResumeNoTasksFound() {
        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        CommandLine cli = new CommandLine(cmd);
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("No interrupted or crashed tasks found for this project.");
    }

    @Test
    @DisplayName("Given single crashed task in non-interactive mode - when resume executed without arguments - reports task with guidance")
    void testResumeSingleTaskNonInteractive() throws IOException {
        String taskId = "TASK-AUTO-RESUME";
        createWorktree(taskId);
        ProjectId projectId = projectResolver.resolveProjectId();

        TaskRun task = TaskRunMother.crashed(projectId.idString(), taskId, TaskStatus.GREEN_PATCH);
        persistenceService.saveTask(task);

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        CommandLine cli = new CommandLine(cmd);
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString())
            .contains("Found interrupted task TASK-AUTO-RESUME in phase [GREEN_PATCH]")
            .contains("Non-interactive environment detected. Run 'dev-agent resume TASK-AUTO-RESUME' to resume.");
    }

    @Test
    @DisplayName("Given single crashed task in interactive mode - when user answers y - resumes task")
    void testResumeSingleTaskInteractiveYes() throws IOException {
        String taskId = "TASK-PROMPT-YES";
        createWorktree(taskId);
        ProjectId projectId = projectResolver.resolveProjectId();

        TaskRun task = TaskRunMother.crashed(projectId.idString(), taskId, TaskStatus.GREEN_PATCH);
        persistenceService.saveTask(task);

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        cmd.setIn(new BufferedReader(new StringReader("y\n")));

        CommandLine cli = new CommandLine(cmd);
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString())
            .contains("Found interrupted task TASK-PROMPT-YES in phase [GREEN_PATCH]")
            .contains("Resuming task: TASK-PROMPT-YES");
    }

    @Test
    @DisplayName("Given single crashed task in interactive mode - when user answers n - aborts resumption")
    void testResumeSingleTaskInteractiveNo() throws IOException {
        String taskId = "TASK-PROMPT-NO";
        createWorktree(taskId);
        ProjectId projectId = projectResolver.resolveProjectId();

        TaskRun task = TaskRunMother.crashed(projectId.idString(), taskId, TaskStatus.RED_TEST);
        persistenceService.saveTask(task);

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        cmd.setIn(new BufferedReader(new StringReader("n\n")));

        CommandLine cli = new CommandLine(cmd);
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute();

        assertThat(exitCode).isEqualTo(1);
        assertThat(sw.toString()).contains("Resumption aborted.");
    }

    @Test
    @DisplayName("Given explicit taskId whose process is still active - reports running error")
    void testResumeExplicitTaskAlreadyRunning() throws IOException {
        String taskId = "TASK-STILL-ALIVE";
        createWorktree(taskId);
        ProjectId projectId = projectResolver.resolveProjectId();

        TaskRun task = TaskRunMother.builder(taskId)
            .projectId(projectId.idString())
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(ProcessHandle.current().pid())
            .build();
        persistenceService.saveTask(task);

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        CommandLine cli = new CommandLine(cmd);
        StringWriter err = new StringWriter();
        cli.setErr(new PrintWriter(err));

        int exitCode = cli.execute(taskId);

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Process (PID " + ProcessHandle.current().pid() + ") is still actively running.");
    }

    @Test
    @DisplayName("Given explicit taskId with missing worktree - reports worktree missing error")
    void testResumeExplicitTaskMissingWorktree() {
        String taskId = "TASK-MISSING-DIR";
        ProjectId projectId = projectResolver.resolveProjectId();

        TaskRun task = TaskRunMother.crashed(projectId.idString(), taskId, TaskStatus.GREEN_PATCH);
        persistenceService.saveTask(task);

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        CommandLine cli = new CommandLine(cmd);
        StringWriter err = new StringWriter();
        cli.setErr(new PrintWriter(err));

        int exitCode = cli.execute(taskId);

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Worktree directory .git/dev-agent-worktrees/TASK-MISSING-DIR is missing.");
    }

    @Test
    @DisplayName("Given explicit valid crashed task - resumes task successfully")
    void testResumeExplicitValidCrashedTask() throws IOException {
        String taskId = "TASK-VALID-CRASH";
        createWorktree(taskId);
        ProjectId projectId = projectResolver.resolveProjectId();

        TaskRun task = TaskRunMother.crashed(projectId.idString(), taskId, TaskStatus.VERIFYING);
        persistenceService.saveTask(task);

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        CommandLine cli = new CommandLine(cmd);
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute(taskId);

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("Resuming task: TASK-VALID-CRASH");
    }

    @Test
    @DisplayName("Given multiple interrupted tasks in interactive mode - when valid selection - resumes selected task")
    void testResumeMultiCandidatesInteractiveSelect() throws IOException {
        createWorktree("TASK-MULTI-1");
        createWorktree("TASK-MULTI-2");
        ProjectId projectId = projectResolver.resolveProjectId();

        persistenceService.saveTask(TaskRunMother.builder("TASK-MULTI-1")
            .projectId(projectId.idString())
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(TaskRunMother.DEAD_PID)
            .createdAt(Instant.now().minusSeconds(60))
            .updatedAt(Instant.now().minusSeconds(50))
            .build());

        persistenceService.saveTask(TaskRunMother.builder("TASK-MULTI-2")
            .projectId(projectId.idString())
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(TaskRunMother.DEAD_PID)
            .createdAt(Instant.now().minusSeconds(30))
            .updatedAt(Instant.now().minusSeconds(10))
            .build());

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        cmd.setIn(new BufferedReader(new StringReader("1\n")));
        CommandLine cli = new CommandLine(cmd);
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("Found 2 interrupted tasks:").contains("Resuming task: TASK-MULTI-2");
    }

    @Test
    @DisplayName("Given multiple interrupted tasks in interactive mode - when invalid selection - reports error")
    void testResumeMultiCandidatesInvalidSelect() throws IOException {
        createWorktree("TASK-INV-1");
        createWorktree("TASK-INV-2");
        ProjectId projectId = projectResolver.resolveProjectId();

        persistenceService.saveTask(TaskRunMother.builder("TASK-INV-1")
            .projectId(projectId.idString())
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(TaskRunMother.DEAD_PID)
            .createdAt(Instant.now().minusSeconds(60))
            .updatedAt(Instant.now().minusSeconds(50))
            .build());

        persistenceService.saveTask(TaskRunMother.builder("TASK-INV-2")
            .projectId(projectId.idString())
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(TaskRunMother.DEAD_PID)
            .createdAt(Instant.now().minusSeconds(30))
            .updatedAt(Instant.now().minusSeconds(10))
            .build());

        ResumeCommand cmd = new ResumeCommand(crashDetector, projectResolver, persistenceService, workspaceResolver);
        cmd.setIn(new BufferedReader(new StringReader("99\n")));
        CommandLine cli = new CommandLine(cmd);
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute();

        assertThat(exitCode).isEqualTo(1);
        assertThat(sw.toString()).contains("Invalid selection.");
    }
}
