package org.quarkus.agent.testutil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunMotherTest {

    @Test
    @DisplayName("Given taskId - when builder invoked - populates standard defaults")
    void testDefaultBuilder() {
        TaskRun task = TaskRunMother.builder("TASK-100").build();

        assertThat(task.taskId()).isEqualTo("TASK-100");
        assertThat(task.projectId()).isEqualTo(TaskRunMother.DEFAULT_PROJECT_ID);
        assertThat(task.branch()).isEqualTo("feature/TASK-100");
        assertThat(task.retryCount()).isZero();
        assertThat(task.createdAt()).isNotNull();
        assertThat(task.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Given taskId and status - when crashed invoked - creates crashed task with dead PID")
    void testCrashedDefaultProject() {
        TaskRun task = TaskRunMother.crashed("TASK-CRASH-01", TaskStatus.RED_TEST);

        assertThat(task.taskId()).isEqualTo("TASK-CRASH-01");
        assertThat(task.projectId()).isEqualTo(TaskRunMother.DEFAULT_PROJECT_ID);
        assertThat(task.status()).isEqualTo(TaskStatus.RED_TEST);
        assertThat(task.currentPhase()).isEqualTo("RED_TEST");
        assertThat(task.pid()).isEqualTo(TaskRunMother.DEAD_PID);
    }

    @Test
    @DisplayName("Given custom projectId, taskId, and status - when crashed invoked - sets custom project")
    void testCrashedCustomProject() {
        TaskRun task = TaskRunMother.crashed("custom/repo", "TASK-CRASH-02", TaskStatus.GREEN_PATCH);

        assertThat(task.projectId()).isEqualTo("custom/repo");
        assertThat(task.taskId()).isEqualTo("TASK-CRASH-02");
        assertThat(task.status()).isEqualTo(TaskStatus.GREEN_PATCH);
        assertThat(task.currentPhase()).isEqualTo("GREEN_PATCH");
        assertThat(task.pid()).isEqualTo(TaskRunMother.DEAD_PID);
    }

    @Test
    @DisplayName("Given taskId and pid - when active invoked - sets running state with given pid")
    void testActiveWithExplicitPid() {
        TaskRun task = TaskRunMother.active("TASK-ACTIVE-01", 12345L);

        assertThat(task.taskId()).isEqualTo("TASK-ACTIVE-01");
        assertThat(task.status()).isEqualTo(TaskStatus.GREEN_PATCH);
        assertThat(task.pid()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Given taskId - when active invoked without pid - uses current JVM pid")
    void testActiveWithCurrentProcessPid() {
        TaskRun task = TaskRunMother.active("TASK-ACTIVE-02");

        assertThat(task.taskId()).isEqualTo("TASK-ACTIVE-02");
        assertThat(task.status()).isEqualTo(TaskStatus.GREEN_PATCH);
        assertThat(task.pid()).isEqualTo(ProcessHandle.current().pid());
    }

    @Test
    @DisplayName("Given taskId - when completed invoked - sets terminal completed status")
    void testCompleted() {
        TaskRun task = TaskRunMother.completed("TASK-DONE-01");

        assertThat(task.taskId()).isEqualTo("TASK-DONE-01");
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.currentPhase()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Given custom projectId and taskId - when completed invoked - sets custom project and terminal completed status")
    void testCompletedCustomProject() {
        TaskRun task = TaskRunMother.completed("custom/repo", "TASK-DONE-02");

        assertThat(task.projectId()).isEqualTo("custom/repo");
        assertThat(task.taskId()).isEqualTo("TASK-DONE-02");
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.currentPhase()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Given taskId - when planning invoked - sets planning status and phase")
    void testPlanning() {
        TaskRun task = TaskRunMother.planning("TASK-PLAN-01");

        assertThat(task.taskId()).isEqualTo("TASK-PLAN-01");
        assertThat(task.projectId()).isEqualTo(TaskRunMother.DEFAULT_PROJECT_ID);
        assertThat(task.status()).isEqualTo(TaskStatus.PLANNING);
        assertThat(task.currentPhase()).isEqualTo("PLANNING");
    }

    @Test
    @DisplayName("Given custom projectId and taskId - when planning invoked - sets custom project and planning status")
    void testPlanningCustomProject() {
        TaskRun task = TaskRunMother.planning("custom/repo", "TASK-PLAN-02");

        assertThat(task.projectId()).isEqualTo("custom/repo");
        assertThat(task.taskId()).isEqualTo("TASK-PLAN-02");
        assertThat(task.status()).isEqualTo(TaskStatus.PLANNING);
        assertThat(task.currentPhase()).isEqualTo("PLANNING");
    }

    @Test
    @DisplayName("Given custom projectId, taskId, and pid - when active invoked - sets custom project and running state")
    void testActiveCustomProject() {
        TaskRun task = TaskRunMother.active("custom/repo", "TASK-ACTIVE-03", 54321L);

        assertThat(task.projectId()).isEqualTo("custom/repo");
        assertThat(task.taskId()).isEqualTo("TASK-ACTIVE-03");
        assertThat(task.status()).isEqualTo(TaskStatus.GREEN_PATCH);
        assertThat(task.pid()).isEqualTo(54321L);
    }

    @Test
    @DisplayName("Given taskId - when aborted invoked - sets terminal aborted status")
    void testAborted() {
        TaskRun task = TaskRunMother.aborted("TASK-ABORT-01");

        assertThat(task.taskId()).isEqualTo("TASK-ABORT-01");
        assertThat(task.status()).isEqualTo(TaskStatus.ABORTED);
        assertThat(task.currentPhase()).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("Given standard builder - when customized - allows overriding fields cleanly")
    void testCustomizationViaBuilder() {
        TaskRun task = TaskRunMother.builder("TASK-CUSTOM")
            .branch("custom-branch")
            .retryCount(3)
            .build();

        assertThat(task.branch()).isEqualTo("custom-branch");
        assertThat(task.retryCount()).isEqualTo(3);
    }
}
