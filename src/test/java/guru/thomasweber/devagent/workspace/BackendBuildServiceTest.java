package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.spi.build.BackendBuildSystem;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.testutil.ProcessResultMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendBuildServiceTest {

    @TempDir
    Path repoRoot;

    @Test
    @DisplayName("Given applicable build system - when compile called - then executes compile command and returns sanitized log")
    void givenApplicableBuildSystem_whenCompileCalled_thenExecutesCompileCommandAndReturnsSanitizedLog() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);
        BackendBuildSystem buildSystem = mock(BackendBuildSystem.class);

        when(contextResolver.resolveRepoRoot()).thenReturn(repoRoot);
        when(contextResolver.resolveBackendBuildSystem(repoRoot)).thenReturn(Optional.of(buildSystem));

        List<String> command = List.of("./mvnw", "-B", "-Dproject.build.directory=target-agent", "-pl", "core", "-am", "test-compile");
        when(buildSystem.buildCompileCommand(repoRoot, "core", "target-agent")).thenReturn(command);

        ProcessResult processResult = ProcessResultMother.success("[INFO] BUILD SUCCESS");
        when(subprocessExecutor.execute(eq(command), eq(repoRoot), any(Duration.class))).thenReturn(processResult);

        SanitizedLog expectedLog = SanitizedLog.builder()
            .exitCode(0)
            .errorCount(0)
            .cleanStdout("[INFO] BUILD SUCCESS")
            .build();
        when(buildSystem.sanitizeOutput(0, "[INFO] BUILD SUCCESS", "")).thenReturn(expectedLog);

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);
        SanitizedLog actualLog = service.compile("core");

        assertThat(actualLog).isEqualTo(expectedLog);
        verify(buildSystem).buildCompileCommand(repoRoot, "core", "target-agent");
        verify(subprocessExecutor).execute(eq(command), eq(repoRoot), any(Duration.class));
    }

    @Test
    @DisplayName("Given applicable build system - when test called - then executes test command with testPattern and returns sanitized log")
    void givenApplicableBuildSystem_whenTestCalled_thenExecutesTestCommandWithTestPatternAndReturnsSanitizedLog() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);
        BackendBuildSystem buildSystem = mock(BackendBuildSystem.class);

        when(contextResolver.resolveRepoRoot()).thenReturn(repoRoot);
        when(contextResolver.resolveBackendBuildSystem(repoRoot)).thenReturn(Optional.of(buildSystem));

        List<String> command = List.of("./mvnw", "-B", "-Dproject.build.directory=target-agent", "test", "-Dtest=OrderServiceTest");
        when(buildSystem.buildTestCommand(repoRoot, null, "OrderServiceTest", "target-agent")).thenReturn(command);

        ProcessResult processResult = ProcessResultMother.failure(1, "[ERROR] Tests run: 1, Failures: 1", "");
        when(subprocessExecutor.execute(eq(command), eq(repoRoot), any(Duration.class))).thenReturn(processResult);

        SanitizedLog expectedLog = SanitizedLog.builder()
            .exitCode(1)
            .errorCount(1)
            .errorLines(List.of("AssertionFailedError"))
            .build();
        when(buildSystem.sanitizeOutput(1, "[ERROR] Tests run: 1, Failures: 1", "")).thenReturn(expectedLog);

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);
        SanitizedLog actualLog = service.test("OrderServiceTest", null);

        assertThat(actualLog).isEqualTo(expectedLog);
        assertThat(actualLog.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("Given no applicable build system - when compile called - then throws IllegalStateException")
    void givenNoApplicableBuildSystem_whenCompileCalled_thenThrowsIllegalStateException() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);

        when(contextResolver.resolveRepoRoot()).thenReturn(repoRoot);
        when(contextResolver.resolveBackendBuildSystem(repoRoot)).thenReturn(Optional.empty());

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);

        assertThatThrownBy(() -> service.compile("core"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No applicable backend build system");
    }

    @Test
    @DisplayName("Given custom projectRoot and targetDir - when compile called - then uses explicit parameters")
    void givenCustomProjectRootAndTargetDir_whenCompileCalled_thenUsesExplicitParameters(@TempDir Path customRoot) {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);
        BackendBuildSystem buildSystem = mock(BackendBuildSystem.class);

        when(contextResolver.resolveBackendBuildSystem(customRoot)).thenReturn(Optional.of(buildSystem));

        List<String> command = List.of("./mvnw", "-B", "-Dproject.build.directory=custom-target", "test-compile");
        when(buildSystem.buildCompileCommand(customRoot, null, "custom-target")).thenReturn(command);

        ProcessResult processResult = ProcessResultMother.success("OK");
        when(subprocessExecutor.execute(eq(command), eq(customRoot), any(Duration.class))).thenReturn(processResult);

        SanitizedLog expectedLog = SanitizedLog.builder().exitCode(0).build();
        when(buildSystem.sanitizeOutput(0, "OK", "")).thenReturn(expectedLog);

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);
        SanitizedLog actualLog = service.compile(customRoot, null, "custom-target");

        assertThat(actualLog).isEqualTo(expectedLog);
    }

    @Test
    @DisplayName("Given custom projectRoot and targetDir - when test called - then uses explicit parameters")
    void givenCustomProjectRootAndTargetDir_whenTestCalled_thenUsesExplicitParameters(@TempDir Path customRoot) {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);
        BackendBuildSystem buildSystem = mock(BackendBuildSystem.class);

        when(contextResolver.resolveBackendBuildSystem(customRoot)).thenReturn(Optional.of(buildSystem));

        List<String> command = List.of("./mvnw", "-B", "-Dproject.build.directory=custom-target", "test", "-Dtest=*Test");
        when(buildSystem.buildTestCommand(customRoot, "app", "*Test", "custom-target")).thenReturn(command);

        ProcessResult processResult = ProcessResultMother.success("OK");
        when(subprocessExecutor.execute(eq(command), eq(customRoot), any(Duration.class))).thenReturn(processResult);

        SanitizedLog expectedLog = SanitizedLog.builder().exitCode(0).build();
        when(buildSystem.sanitizeOutput(0, "OK", "")).thenReturn(expectedLog);

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);
        SanitizedLog actualLog = service.test(customRoot, "app", "*Test", "custom-target");

        assertThat(actualLog).isEqualTo(expectedLog);
    }

    @Test
    @DisplayName("Given null or blank parameters in explicit overloads - when called - then falls back to defaults")
    void givenNullOrBlankParametersInExplicitOverloads_whenCalled_thenFallsBackToDefaults() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);
        BackendBuildSystem buildSystem = mock(BackendBuildSystem.class);

        when(contextResolver.resolveRepoRoot()).thenReturn(repoRoot);
        when(contextResolver.resolveBackendBuildSystem(repoRoot)).thenReturn(Optional.of(buildSystem));

        ProcessResult processResult = ProcessResultMother.success("");
        when(subprocessExecutor.execute(any(), eq(repoRoot), any(Duration.class))).thenReturn(processResult);
        SanitizedLog expectedLog = SanitizedLog.builder().exitCode(0).build();
        when(buildSystem.sanitizeOutput(eq(0), any(), any())).thenReturn(expectedLog);

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);

        // Test compile with null root, null target, blank target
        service.compile(null, "mod", null);
        service.compile(null, "mod", "   ");

        // Test test with null root, null target, blank target
        service.test(null, "mod", "*Test", null);
        service.test(null, "mod", "*Test", "   ");

        verify(buildSystem, atLeast(2)).buildCompileCommand(repoRoot, "mod", "target-agent");
        verify(buildSystem, atLeast(2)).buildTestCommand(repoRoot, "mod", "*Test", "target-agent");
    }

    @Test
    @DisplayName("Given subprocess timeout - when executed - then injects timeout notice and exit code 124")
    void givenSubprocessTimeout_whenExecuted_thenInjectsTimeoutNoticeAndExitCode124() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);
        BackendBuildSystem buildSystem = mock(BackendBuildSystem.class);

        when(contextResolver.resolveRepoRoot()).thenReturn(repoRoot);
        when(contextResolver.resolveBackendBuildSystem(repoRoot)).thenReturn(Optional.of(buildSystem));

        List<String> command = List.of("./mvnw", "test-compile");
        when(buildSystem.buildCompileCommand(repoRoot, null, "target-agent")).thenReturn(command);

        ProcessResult timedOutResult = ProcessResultMother.timedOut();
        when(subprocessExecutor.execute(eq(command), eq(repoRoot), any(Duration.class))).thenReturn(timedOutResult);

        SanitizedLog baseLog = SanitizedLog.builder().exitCode(0).build();
        when(buildSystem.sanitizeOutput(124, "", "Process timed out")).thenReturn(baseLog);

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);
        SanitizedLog actualLog = service.compile((String) null);

        assertThat(actualLog.exitCode()).isEqualTo(124);
        assertThat(actualLog.errorLines()).contains("Build process timed out after 5 minutes");
        assertThat(actualLog.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("Given truncated subprocess output - when executed - then appends truncation notice to error lines")
    void givenTruncatedSubprocessOutput_whenExecuted_thenAppendsTruncationNotice() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        SubprocessExecutor subprocessExecutor = mock(SubprocessExecutor.class);
        BackendBuildSystem buildSystem = mock(BackendBuildSystem.class);

        when(contextResolver.resolveRepoRoot()).thenReturn(repoRoot);
        when(contextResolver.resolveBackendBuildSystem(repoRoot)).thenReturn(Optional.of(buildSystem));

        List<String> command = List.of("./mvnw", "test");
        when(buildSystem.buildTestCommand(repoRoot, null, null, "target-agent")).thenReturn(command);

        ProcessResult truncatedResult = ProcessResultMother.truncated("huge output");
        when(subprocessExecutor.execute(eq(command), eq(repoRoot), any(Duration.class))).thenReturn(truncatedResult);

        SanitizedLog baseLog = SanitizedLog.builder().exitCode(0).build();
        when(buildSystem.sanitizeOutput(0, "huge output", "")).thenReturn(baseLog);

        BackendBuildService service = new BackendBuildService(contextResolver, subprocessExecutor);
        SanitizedLog actualLog = service.test(null, null);

        assertThat(actualLog.errorLines()).contains("Build process output was truncated due to buffer limits");
    }
}
