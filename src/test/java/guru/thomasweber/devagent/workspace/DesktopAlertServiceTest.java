package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.testutil.ProcessResultMother;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DesktopAlertServiceTest {

    @TempDir
    Path tempDir;

    private String originalOsName;

    @BeforeEach
    void captureOsName() {
        originalOsName = System.getProperty("os.name");
    }

    @AfterEach
    void restoreOsName() {
        if (originalOsName != null) {
            System.setProperty("os.name", originalOsName);
        }
    }

    @Test
    @DisplayName("Given Linux desktop environment - when notifyTaskOutcome called for COMPLETED task - then dispatches notify-send with redacted body")
    @SuppressWarnings("unchecked")
    void givenLinuxDesktop_whenTaskCompleted_thenDispatchesNotifySendWithRedaction() {
        System.setProperty("os.name", "Linux");
        SubprocessExecutor executor = mock(SubprocessExecutor.class);
        when(executor.execute(any(), any(), any(Duration.class))).thenReturn(ProcessResultMother.success(""));

        DesktopAlertService service = new DesktopAlertService(
            executor,
            new SecretRedactionFilter(),
            new DevAgentConfigLoader(),
            Map.of("DISPLAY", ":0")::get
        );

        WorktreeState state = WorktreeStateMother.completed("TASK-601")
            .toBuilder()
            .lastMessage("Done with token ghp_1234567890abcdefghijklmnopqrstuvwxyz1234")
            .build();
        service.notifyTaskOutcome(tempDir, state);

        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor).execute(cmdCaptor.capture(), eq(tempDir), eq(Duration.ofSeconds(3)));
        assertThat(cmdCaptor.getValue())
            .containsExactly(
                "notify-send",
                "--app-name=dev-agent",
                "--urgency=normal",
                "--",
                "dev-agent: TASK-601 COMPLETED",
                "Done with token ***REDACTED_CREDENTIAL***"
            );

        WorktreeState dashMsg = WorktreeStateMother.completed("TASK-601")
            .toBuilder()
            .lastMessage("--expire-time=9999999")
            .build();
        service.notifyTaskOutcome(tempDir, dashMsg);
        verify(executor, times(2)).execute(cmdCaptor.capture(), eq(tempDir), eq(Duration.ofSeconds(3)));
        assertThat(cmdCaptor.getValue().get(3)).isEqualTo("--");
        assertThat(cmdCaptor.getValue().get(5)).isEqualTo("--expire-time=9999999");
    }

    @Test
    @DisplayName("Given headless Linux environment - when notifyTaskOutcome called - then skips subprocess execution")
    void givenHeadlessLinux_whenNotifyCalled_thenSkipsSubprocess() {
        System.setProperty("os.name", "Linux");
        SubprocessExecutor executor = mock(SubprocessExecutor.class);

        DesktopAlertService service = new DesktopAlertService(
            executor,
            new SecretRedactionFilter(),
            new DevAgentConfigLoader(),
            _ -> null
        );

        service.notifyTaskOutcome(tempDir, WorktreeStateMother.completed("TASK-602"));
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("Given macOS environment and FAILED task with quotes - when notifyTaskOutcome called - then escapes AppleScript safely")
    @SuppressWarnings("unchecked")
    void givenMacOsAndFailedTask_whenNotifyCalled_thenEscapesAppleScript() {
        System.setProperty("os.name", "Mac OS X");
        SubprocessExecutor executor = mock(SubprocessExecutor.class);
        when(executor.execute(any(), any(), any(Duration.class)))
            .thenReturn(ProcessResultMother.failure(1, "", "osascript missing"));

        DesktopAlertService service = new DesktopAlertService(
            executor,
            new SecretRedactionFilter(),
            new DevAgentConfigLoader(),
            _ -> null
        );

        WorktreeState failed = WorktreeStateMother.builder("TASK-603")
            .status(TaskStatus.FAILED)
            .lastMessage("Build \"failed\" at C:\\repo")
            .build();
        service.notifyTaskOutcome(tempDir, failed);

        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor).execute(cmdCaptor.capture(), eq(tempDir), eq(Duration.ofSeconds(3)));
        assertThat(cmdCaptor.getValue().get(0)).isEqualTo("osascript");
        assertThat(cmdCaptor.getValue().get(1)).isEqualTo("-e");
        assertThat(cmdCaptor.getValue().get(2))
            .contains("display notification \"Build \\\"failed\\\" at C:\\\\repo\"")
            .contains("with title \"dev-agent: TASK-603 FAILED\"");
    }

    @Test
    @DisplayName("Given Windows environment - when notifyTaskOutcome called - then dispatches PowerShell notification command")
    @SuppressWarnings("unchecked")
    void givenWindows_whenNotifyCalled_thenDispatchesPowerShell() {
        System.setProperty("os.name", "Windows 11");
        SubprocessExecutor executor = mock(SubprocessExecutor.class);
        when(executor.execute(any(), any(), any(Duration.class))).thenReturn(ProcessResultMother.success(""));

        DesktopAlertService service = new DesktopAlertService(
            executor,
            new SecretRedactionFilter(),
            new DevAgentConfigLoader(),
            _ -> null
        );

        WorktreeState failed = WorktreeStateMother.builder("TASK-604")
            .status(TaskStatus.ABORTED)
            .lastMessage("User's \u2019abort <tag> & request")
            .build();
        service.notifyTaskOutcome(tempDir, failed);

        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor).execute(cmdCaptor.capture(), eq(tempDir), eq(Duration.ofSeconds(3)));
        assertThat(cmdCaptor.getValue().get(0)).isEqualTo("powershell");
        assertThat(cmdCaptor.getValue().get(2)).isEqualTo("-Command");
        assertThat(cmdCaptor.getValue().get(3))
            .contains("User''s ''abort <tag> & request", "CreateToastNotifier", ".Show(")
            .doesNotContain("\u2019", "&lt;", "&amp;");
    }

    @Test
    @DisplayName("Given notifications disabled or non-terminal state - when notifyTaskOutcome called - then skips notification")
    void givenDisabledConfigOrNonTerminalState_whenNotifyCalled_thenSkips() throws IOException {
        System.setProperty("os.name", "Linux");
        SubprocessExecutor executor = mock(SubprocessExecutor.class);
        Files.createDirectories(tempDir.resolve(".agents"));
        Files.writeString(tempDir.resolve(".agents/dev-agent.yaml"), """
            agent:
              notifications:
                desktop-enabled: false
            """);

        DesktopAlertService service = new DesktopAlertService(
            executor,
            new SecretRedactionFilter(),
            new DevAgentConfigLoader(),
            Map.of("WAYLAND_DISPLAY", "wayland-0")::get
        );

        service.notifyTaskOutcome(tempDir, WorktreeStateMother.completed("TASK-605"));
        verifyNoInteractions(executor);

        Files.writeString(tempDir.resolve(".agents/dev-agent.yaml"), """
            agent:
              notifications:
                desktop-enabled: true
                on-success: false
                on-failure: true
            """);
        service.notifyTaskOutcome(tempDir, WorktreeStateMother.completed("TASK-606"));
        service.notifyTaskOutcome(tempDir, WorktreeStateMother.active("TASK-607", 123L));
        service.notifyTaskOutcome(tempDir, null);
        verifyNoInteractions(executor);

        // Verify failure alert still fires when on-success=false and on-failure=true, and null repoRoot/blank message works
        when(executor.execute(any(), any(), any(Duration.class))).thenReturn(ProcessResultMother.success(""));
        service.notifyTaskOutcome(null, WorktreeStateMother.builder("TASK-608").status(TaskStatus.FAILED).lastMessage("  ").build());
        verify(executor).execute(any(), eq(null), eq(Duration.ofSeconds(3)));

        DesktopAlertService defaultService = new DesktopAlertService();
        assertThat(defaultService).isNotNull();
    }
}
