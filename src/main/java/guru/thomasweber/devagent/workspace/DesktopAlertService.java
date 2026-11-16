package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static java.util.Objects.isNull;

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
    private final Function<String, String> envLookup;

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
        Function<String, String> envLookup
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
        DevAgentConfig.NotificationsConfig notifications = loadConfig(repoRoot).notifications();
        if (!notifications.desktopEnabled() || !shouldNotifyStatus(state.status(), notifications)) {
            return;
        }

        String title = "dev-agent: " + state.taskId() + " " + state.status().name();
        String rawBody = (!isNull(state.lastMessage()) && !state.lastMessage().isBlank())
            ? state.lastMessage()
            : "Task finished with status " + state.status().name();
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

    private DevAgentConfig loadConfig(Path repoRoot) {
        if (isNull(repoRoot)) {
            return configLoader.loadFromYaml("");
        }
        Path configFile = repoRoot.resolve(".agents").resolve("dev-agent.yaml");
        return Files.isRegularFile(configFile) ? configLoader.load(configFile) : configLoader.loadFromYaml("");
    }

    private boolean shouldNotifyStatus(TaskStatus status, DevAgentConfig.NotificationsConfig notifications) {
        return status == TaskStatus.COMPLETED ? notifications.onSuccess() : notifications.onFailure();
    }

    private List<String> buildPlatformCommand(TaskStatus status, String title, String body) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            String script = "display notification \"" + escapeAppleScript(body)
                + "\" with title \"" + escapeAppleScript(title) + "\"";
            return List.of("osascript", "-e", script);
        }
        if (osName.contains("win")) {
            String psScript = "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null; "
                + "Write-Output '" + escapePowerShellSingleQuoted(title + ": " + body) + "'";
            return List.of("powershell", "-NoProfile", "-Command", psScript);
        }
        if (!hasLinuxDisplaySession()) {
            LOG.debug("Skipping desktop notification in headless Linux session");
            return List.of();
        }
        String urgency = (status == TaskStatus.COMPLETED) ? "normal" : "critical";
        return List.of("notify-send", "--app-name=dev-agent", "--urgency=" + urgency, title, body);
    }

    private boolean hasLinuxDisplaySession() {
        return isSet("DISPLAY") || isSet("WAYLAND_DISPLAY") || isSet("DBUS_SESSION_BUS_ADDRESS");
    }

    private boolean isSet(String envKey) {
        String value = envLookup.apply(envKey);
        return !isNull(value) && !value.isBlank();
    }

    private String escapeAppleScript(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapePowerShellSingleQuoted(String value) {
        return value.replace("'", "''");
    }
}
