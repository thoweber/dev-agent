package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Cross-platform, zero-JNI desktop alert dispatcher using {@link SubprocessExecutor}
 * so native GraalVM binaries remain AWT-free and headless CI environments skip safely.
 */
@ApplicationScoped
public class DesktopAlertService {

    private static final Logger LOG = Logger.getLogger(DesktopAlertService.class);
    private static final Duration ALERT_TIMEOUT = Duration.ofSeconds(3);

    private final SubprocessExecutor subprocessExecutor;
    private final SecretRedactionFilter secretFilter;
    private final DevAgentConfigLoader configLoader;
    private final UnaryOperator<String> envLookup;

    @Inject
    public DesktopAlertService(
        SubprocessExecutor subprocessExecutor,
        SecretRedactionFilter secretFilter,
        DevAgentConfigLoader configLoader
    ) {
        this(subprocessExecutor, secretFilter, configLoader, System::getenv);
    }

    public DesktopAlertService(
        SubprocessExecutor subprocessExecutor,
        SecretRedactionFilter secretFilter,
        DevAgentConfigLoader configLoader,
        UnaryOperator<String> envLookup
    ) {
        this.subprocessExecutor = subprocessExecutor;
        this.secretFilter = secretFilter;
        this.configLoader = configLoader;
        this.envLookup = envLookup;
    }

    public DesktopAlertService() {
        this(new SubprocessExecutor(), new SecretRedactionFilter(), new DevAgentConfigLoader(), System::getenv);
    }

    public void notifyTaskOutcome(Path repoRoot, WorktreeState state) {
        if (isNull(state) || isNull(state.status()) || !state.status().isTerminal()) {
            return;
        }
        DevAgentConfig.NotificationsConfig notifications = configLoader.loadForWorkspace(repoRoot).notifications();
        if (!notifications.desktopEnabled() || !shouldNotifyStatus(state.status(), notifications)) {
            return;
        }

        String title = "dev-agent: " + state.taskId() + " " + state.status().name();
        String rawBody = "Task finished with status " + state.status().name();
        if (nonNull(state.lastMessage()) && !state.lastMessage().isBlank()) {
            rawBody = state.lastMessage();
        }
        String sanitizedTitle = secretFilter.sanitize(title).value();
        String sanitizedBody = secretFilter.sanitize(rawBody).value();

        List<String> command = buildPlatformCommand(state.status(), sanitizedTitle, sanitizedBody);
        if (command.isEmpty()) {
            return;
        }

        ProcessResult result = subprocessExecutor.execute(command, repoRoot, ALERT_TIMEOUT);
        if (!result.isSuccess()) {
            LOG.debugf("Desktop notification command exited with code %d: %s", result.exitCode(), result.stdErr());
        }
    }

    private boolean shouldNotifyStatus(TaskStatus status, DevAgentConfig.NotificationsConfig notifications) {
        if (status == TaskStatus.COMPLETED) {
            return notifications.onSuccess();
        }
        return notifications.onFailure();
    }

    private List<String> buildPlatformCommand(TaskStatus status, String title, String body) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            String script = "display notification \"" + escapeAppleScript(body)
                + "\" with title \"" + escapeAppleScript(title) + "\"";
            return List.of("osascript", "-e", script);
        }
        if (osName.contains("win")) {
            String safeTitle = escapePowerShellSingleQuoted(title);
            String safeBody = escapePowerShellSingleQuoted(body);
            String psScript = "$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent("
                + "[Windows.UI.Notifications.ToastTemplateType]::ToastText02); "
                + "$nodes = $xml.GetElementsByTagName('text'); "
                + "$nodes.Item(0).AppendChild($xml.CreateTextNode('" + safeTitle + "')) | Out-Null; "
                + "$nodes.Item(1).AppendChild($xml.CreateTextNode('" + safeBody + "')) | Out-Null; "
                + "$toast = [Windows.UI.Notifications.ToastNotification]::new($xml); "
                + "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('dev-agent').Show($toast)";
            return List.of("powershell", "-NoProfile", "-Command", psScript);
        }
        if (!hasLinuxDisplaySession()) {
            LOG.debug("Skipping desktop notification in headless Linux session");
            return List.of();
        }
        String urgency = "critical";
        if (status == TaskStatus.COMPLETED) {
            urgency = "normal";
        }
        return List.of("notify-send", "--app-name=dev-agent", "--urgency=" + urgency, "--", title, body);
    }

    private boolean hasLinuxDisplaySession() {
        return isSet("DISPLAY") || isSet("WAYLAND_DISPLAY") || isSet("DBUS_SESSION_BUS_ADDRESS");
    }

    private boolean isSet(String envKey) {
        String value = envLookup.apply(envKey);
        return nonNull(value) && !value.isBlank();
    }

    private String escapeAppleScript(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapePowerShellSingleQuoted(String value) {
        return value
            .replace("\u2018", "'")
            .replace("\u2019", "'")
            .replace("\u201A", "'")
            .replace("\u201B", "'")
            .replace("'", "''");
    }
}
