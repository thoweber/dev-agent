package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given auto-resume with no candidates - prints helpful message and exits 0")
    void testAutoResumeNoCandidates() {
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of();
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("No interrupted or crashed tasks found");
    }

    @Test
    @DisplayName("Given single candidate in non-interactive shell - displays guidance and exits 0")
    void testSingleCandidateNonInteractive() {
        TaskRun task = TaskRunMother.crashed("TASK-101", TaskStatus.RED_TEST);
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString())
            .contains("Found interrupted task TASK-101")
            .contains("Non-interactive environment detected");
    }

    @Test
    @DisplayName("Given single candidate in interactive shell - prompts and accepts 'y'")
    void testSingleCandidateInteractiveAccept() {
        TaskRun task = TaskRunMother.crashed("TASK-102", TaskStatus.GREEN_PATCH);
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        cmd.setIn(new BufferedReader(new StringReader("y\n")));

        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("[dev-agent] Resuming task: TASK-102");
    }

    @Test
    @DisplayName("Given single candidate in interactive shell - prompts and declines with 'n'")
    void testSingleCandidateInteractiveDecline() {
        TaskRun task = TaskRunMother.crashed("TASK-103", TaskStatus.GREEN_PATCH);
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        cmd.setIn(new BufferedReader(new StringReader("n\n")));

        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("[dev-agent] Resumption aborted.");
    }

    @Test
    @DisplayName("Given multiple candidates in non-interactive shell - lists candidates and prompts for task ID")
    void testMultiCandidatesNonInteractive() {
        TaskRun task1 = TaskRunMother.builder("TASK-201").currentPhase("RED_TEST").build();
        TaskRun task2 = TaskRunMother.builder("TASK-202").currentPhase("GREEN_PATCH").build();
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task1, task2);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString())
            .contains("Found 2 interrupted tasks")
            .contains("TASK-201")
            .contains("TASK-202")
            .contains("Non-interactive environment detected");
    }

    @Test
    @DisplayName("Given multiple candidates in interactive shell - allows choosing option 2")
    void testMultiCandidatesInteractiveSelect() {
        TaskRun task1 = TaskRunMother.builder("TASK-301").currentPhase("PLANNING").build();
        TaskRun task2 = TaskRunMother.builder("TASK-302").currentPhase("REFACTOR").build();
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task1, task2);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        cmd.setIn(new BufferedReader(new StringReader("2\n")));

        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("[dev-agent] Resuming task: TASK-302");
    }

    @Test
    @DisplayName("Given multiple candidates in interactive shell - invalid number outputs error")
    void testMultiCandidatesInteractiveInvalid() {
        TaskRun task1 = TaskRunMother.builder("TASK-401").currentPhase("PLANNING").build();
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task1, task1);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        cmd.setIn(new BufferedReader(new StringReader("99\n")));

        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("[dev-agent] Invalid selection.");
    }

    @Test
    @DisplayName("Given multiple candidates in interactive shell - non-numeric or out of bounds option fails")
    void testMultiCandidatesInteractiveNonNumericAndZero() {
        TaskRun task1 = TaskRunMother.builder("TASK-402").currentPhase("PLANNING").build();
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task1, task1);
            }
        };

        // Test non-numeric input "abc"
        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        cmd.setIn(new BufferedReader(new StringReader("abc\n")));

        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));
        int exitCode = cl.execute();

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("[dev-agent] Resumption aborted.");

        // Test out of bounds 0
        cmd.setIn(new BufferedReader(new StringReader("0\n")));
        StringWriter out2 = new StringWriter();
        CommandLine cl2 = new CommandLine(cmd);
        cl2.setOut(new PrintWriter(out2));
        int exitCode2 = cl2.execute();

        assertThat(exitCode2).isEqualTo(1);
        assertThat(out2.toString()).contains("[dev-agent] Invalid selection.");
    }

    @Test
    @DisplayName("Given single candidate with null currentPhase - falls back to status name")
    void testSingleCandidateNullPhaseFallsBackToStatus() {
        TaskRun task = TaskRunMother.builder("TASK-105").status(TaskStatus.RED_TEST).currentPhase(null).build();
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("in phase [RED_TEST]");
    }

    @Test
    @DisplayName("Given multiple candidates in interactive shell - 'q' aborts")
    void testMultiCandidatesInteractiveAbort() {
        TaskRun task1 = TaskRunMother.builder("TASK-501").currentPhase("PLANNING").build();
        CrashRecoveryDetector crashDetector = new CrashRecoveryDetector() {
            @Override
            public List<TaskRun> findAllResumableTasks(ProjectId projectId) {
                return List.of(task1, task1);
            }
        };

        ResumeCommand cmd = new ResumeCommand(crashDetector, new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), new TaskPersistenceService(), new WorkspaceContextResolver(tempDir));
        cmd.setIn(new BufferedReader(new StringReader("q\n")));

        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute();

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("[dev-agent] Resumption aborted.");
    }

    @Test
    @DisplayName("Given explicit task ID - when not found in persistence - exits with error")
    void testExplicitTaskNotFound() {
        TaskPersistenceService mockPersistence = new TaskPersistenceService() {
            @Override
            public Optional<TaskRun> findByProjectAndTask(ProjectId projectId, String taskId) {
                return Optional.empty();
            }
        };

        ResumeCommand cmd = new ResumeCommand(new CrashRecoveryDetector(), new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), mockPersistence, new WorkspaceContextResolver(tempDir));
        StringWriter err = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setErr(new PrintWriter(err));

        int exitCode = cl.execute("TASK-NOTFOUND");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Task not found: TASK-NOTFOUND");
    }

    @Test
    @DisplayName("Given explicit task ID - when terminal or process alive or worktree missing - fails validation")
    void testExplicitTaskValidationFailures() {
        TaskRun terminalTask = TaskRunMother.completed("TASK-TERM");
        TaskRun aliveTask = TaskRunMother.active("TASK-ALIVE");
        TaskRun missingWorktree = TaskRunMother.crashed("TASK-NOWORKTREE", TaskStatus.GREEN_PATCH);

        TaskPersistenceService mockPersistence = new TaskPersistenceService() {
            @Override
            public Optional<TaskRun> findByProjectAndTask(ProjectId projectId, String taskId) {
                if ("TASK-TERM".equals(taskId)) return Optional.of(terminalTask);
                if ("TASK-ALIVE".equals(taskId)) return Optional.of(aliveTask);
                if ("TASK-NOWORKTREE".equals(taskId)) return Optional.of(missingWorktree);
                return Optional.empty();
            }
        };

        ResumeCommand cmd = new ResumeCommand(new CrashRecoveryDetector(), new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), mockPersistence, new WorkspaceContextResolver(tempDir));

        StringWriter err = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setErr(new PrintWriter(err));

        assertThat(cl.execute("TASK-TERM")).isEqualTo(1);
        assertThat(err.toString()).contains("Task has already completed");

        err.getBuffer().setLength(0);
        assertThat(cl.execute("TASK-ALIVE")).isEqualTo(1);
        assertThat(err.toString()).contains("is still actively running");

        err.getBuffer().setLength(0);
        assertThat(cl.execute("TASK-NOWORKTREE")).isEqualTo(1);
        assertThat(err.toString()).contains("Worktree directory .git/dev-agent-worktrees/TASK-NOWORKTREE is missing");
    }

    @Test
    @DisplayName("Given explicit task ID - when valid and worktree present - succeeds with code 0")
    void testExplicitTaskSuccess() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        WorktreeTestFixture.createWorktree(tempDir, "TASK-OK");

        TaskRun validTask = TaskRunMother.crashed("TASK-OK", TaskStatus.RED_TEST);

        TaskPersistenceService mockPersistence = new TaskPersistenceService() {
            @Override
            public Optional<TaskRun> findByProjectAndTask(ProjectId projectId, String taskId) {
                return Optional.of(validTask);
            }
        };

        ResumeCommand cmd = new ResumeCommand(new CrashRecoveryDetector(), new ProjectIdResolver(new WorkspaceContextResolver(tempDir)), mockPersistence, workspace);
        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out));

        int exitCode = cl.execute("TASK-OK");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("[dev-agent] Resuming task: TASK-OK");

        // Verify run() method delegates cleanly to call()
        cmd.run();
    }
}
