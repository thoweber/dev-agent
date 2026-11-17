package guru.thomasweber.devagent.logging;

import guru.thomasweber.devagent.config.DevAgentConfig.LoggingConfig;
import guru.thomasweber.devagent.model.LogVerbosity;
import guru.thomasweber.devagent.model.SessionId;
import guru.thomasweber.devagent.model.SessionLoggingHandle;
import guru.thomasweber.devagent.model.SessionLoggingRequest;
import guru.thomasweber.devagent.workspace.PathGuard;
import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import guru.thomasweber.devagent.workspace.WorkspaceException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logmanager.Level;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.jboss.logmanager.handlers.ConsoleHandler;
import org.jboss.logmanager.handlers.ConsoleHandler.Target;
import org.jboss.logmanager.handlers.FileHandler;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.logging.Level.ALL;
import static java.util.logging.Level.OFF;

/**
 * Manages opt-in, secret-redacted per-session file and optional stderr logging handlers.
 */
@ApplicationScoped
public class SessionLoggingService implements AutoCloseable {

    public static final String DEV_AGENT_PACKAGE = "guru.thomasweber.devagent";
    private static final String LOG_DIR_NAME = ".config/dev-agent/logs";
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern SESSION_LOG_FILE = Pattern.compile("^\\d{8}-\\d{9}-[A-Za-z0-9][A-Za-z0-9._-]*\\.log$");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final Set<PosixFilePermission> OWNER_FILE_PERMS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );
    private static final Set<PosixFilePermission> OWNER_DIR_PERMS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );

    private final LogContext logContext;
    private final SecretRedactionFilter secretFilter;
    private final Path defaultLogDir;
    private final Clock clock;
    private final List<Handler> activeHandlers = new ArrayList<>();
    private SessionLoggingHandle activeHandle;

    @Inject
    public SessionLoggingService(SecretRedactionFilter secretFilter) {
        this(
            LogContext.getLogContext(),
            secretFilter,
            defaultUserLogDir(),
            Clock.systemDefaultZone()
        );
    }

    public SessionLoggingService() {
        this(new SecretRedactionFilter());
    }

    public SessionLoggingService(
        LogContext logContext,
        SecretRedactionFilter secretFilter,
        Path defaultLogDir,
        Clock clock
    ) {
        this.logContext = Objects.requireNonNullElseGet(logContext, LogContext::getLogContext);
        this.secretFilter = Objects.requireNonNullElseGet(secretFilter, SecretRedactionFilter::new);
        this.defaultLogDir = Objects.requireNonNullElseGet(defaultLogDir, SessionLoggingService::defaultUserLogDir);
        this.clock = Objects.requireNonNullElseGet(clock, Clock::systemDefaultZone);
    }

    private static Path defaultUserLogDir() {
        return Path.of(System.getProperty("user.home", "."), LOG_DIR_NAME);
    }

    public synchronized SessionLoggingHandle configureSession(SessionLoggingRequest request) {
        close();

        SessionLoggingRequest effective = Objects.requireNonNullElseGet(request, () -> SessionLoggingRequest.builder().build());
        SessionId sessionId = resolveSafeSessionId(effective.sessionId());
        LoggingConfig loggingConfig = effective.loggingConfig();
        LogVerbosity verbosity = LogVerbosity.resolveEffective(effective.cliVerbosityCount(), loggingConfig);

        Logger rootLogger = logContext.getLogger("");
        Logger appLogger = logContext.getLogger(DEV_AGENT_PACKAGE);
        rootLogger.setLevel(verbosity.rootLevel());
        appLogger.setLevel(verbosity.devAgentLevel());

        if (!verbosity.isEnabled()) {
            activeHandle = SessionLoggingHandle.builder()
                .sessionId(sessionId)
                .verbosity(LogVerbosity.OFF)
                .sessionLogFile(null)
                .consoleEnabled(false)
                .build();
            return activeHandle;
        }

        boolean fileEnabled = isNull(loggingConfig) || loggingConfig.fileEnabled();
        boolean consoleEnabled = nonNull(loggingConfig) && loggingConfig.consoleEnabled();
        Formatter formatter = new SecretRedactingFormatter(secretFilter);
        Path sessionLogFile = null;

        if (fileEnabled) {
            Path logDir = resolveAndValidateLogDir(effective.workspaceRoot(), loggingConfig);
            int maxFiles = nonNull(loggingConfig) ? loggingConfig.maxFiles() : 50;
            sessionLogFile = openSessionFileHandler(rootLogger, logDir, sessionId, maxFiles, formatter);
        }

        if (consoleEnabled) {
            openConsoleHandler(rootLogger, formatter);
        }

        activeHandle = SessionLoggingHandle.builder()
            .sessionId(sessionId)
            .verbosity(verbosity)
            .sessionLogFile(sessionLogFile)
            .consoleEnabled(consoleEnabled)
            .build();

        String commandName = (isNull(effective.commandName()) || effective.commandName().isBlank())
            ? "dev-agent"
            : effective.commandName().strip();
        appLogger.log(
            Level.INFO,
            String.format("Starting dev-agent session %s (command=%s, verbosity=%s)", sessionId.value(), commandName, verbosity)
        );
        appLogger.log(
            Level.DEBUG,
            String.format("Session logging sinks configured (file=%s, stderr=%s)", sessionLogFile, consoleEnabled)
        );
        return activeHandle;
    }

    public synchronized int activeVerbosityCount() {
        if (isNull(activeHandle)) {
            return 0;
        }
        return activeHandle.verbosity().verbosityCount();
    }

    public synchronized SessionLoggingHandle currentHandle() {
        return activeHandle;
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        Logger rootLogger = logContext.getLogger("");
        for (Handler handler : activeHandlers) {
            rootLogger.removeHandler(handler);
            try {
                handler.flush();
            } finally {
                handler.close();
            }
        }
        activeHandlers.clear();
        rootLogger.setLevel(OFF);
        logContext.getLogger(DEV_AGENT_PACKAGE).setLevel(OFF);
        activeHandle = null;
    }

    private SessionId resolveSafeSessionId(SessionId provided) {
        if (isNull(provided)) {
            return SessionId.of("sess-" + UUID.randomUUID().toString().substring(0, 12));
        }
        String raw = provided.value();
        if (raw.contains("..") || !SAFE_SESSION_ID.matcher(raw).matches()) {
            throw new SecurityException("Unsafe session identifier for log file naming: " + raw);
        }
        return provided;
    }

    private Path resolveAndValidateLogDir(Path workspaceRoot, LoggingConfig loggingConfig) {
        if (isNull(loggingConfig) || loggingConfig.dir().isEmpty() || loggingConfig.dir().get().isBlank()) {
            return assertParentAndTargetNoSymlinks(defaultLogDir);
        }
        String rawDir = loggingConfig.dir().get().strip();
        if (rawDir.equals("~") || rawDir.startsWith("~/") || rawDir.startsWith("~\\")) {
            Path userHome = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
            Path expanded = rawDir.equals("~")
                ? userHome
                : userHome.resolve(rawDir.substring(2)).normalize();
            assertNoSymlinks(userHome, expanded);
            return expanded;
        }

        Path candidate = Path.of(rawDir);
        if (candidate.isAbsolute()) {
            return assertParentAndTargetNoSymlinks(candidate.normalize());
        }

        Path effectiveRoot = nonNull(workspaceRoot)
            ? PathGuard.toRealOrNormalized(workspaceRoot)
            : Path.of(".").toAbsolutePath().normalize();
        Path resolved = effectiveRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(effectiveRoot)) {
            throw new SecurityException("Configured log directory escapes workspace root: " + rawDir);
        }
        assertNoSymlinks(effectiveRoot, resolved);
        return resolved;
    }

    private Path assertParentAndTargetNoSymlinks(Path target) {
        Path parent = target.getParent();
        if (nonNull(parent)) {
            rejectIfSymbolicLink(parent);
        }
        assertNoSymlinks(parent, target);
        return target;
    }

    private void assertNoSymlinks(Path boundary, Path target) {
        Path current = target;
        if (nonNull(boundary) && target.startsWith(boundary)) {
            current = boundary;
            for (Path segment : boundary.relativize(target)) {
                current = current.resolve(segment);
                rejectIfSymbolicLink(current);
            }
            return;
        }
        rejectIfSymbolicLink(current);
    }

    private void rejectIfSymbolicLink(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new SecurityException("Refusing to write session logs through symbolic link: " + path);
        }
    }

    private Path openSessionFileHandler(
        Logger rootLogger,
        Path logDir,
        SessionId sessionId,
        int maxFiles,
        Formatter formatter
    ) {
        try {
            Files.createDirectories(logDir);
            applyPosixPermissions(logDir, OWNER_DIR_PERMS);
            pruneOldSessionLogs(logDir, maxFiles);

            Path sessionFile = allocateUniqueLogFile(logDir, sessionId);
            Files.createFile(sessionFile);
            applyPosixPermissions(sessionFile, OWNER_FILE_PERMS);

            FileHandler fileHandler = new FileHandler(formatter, sessionFile.toFile(), true);
            fileHandler.setEncoding(StandardCharsets.UTF_8.name());
            fileHandler.setLevel(ALL);
            rootLogger.addHandler(fileHandler);
            activeHandlers.add(fileHandler);
            return sessionFile;
        } catch (IOException e) {
            throw new WorkspaceException("Failed to initialize per-session log file under " + logDir, e);
        }
    }

    private Path allocateUniqueLogFile(Path logDir, SessionId sessionId) {
        String timestamp = FILE_TIMESTAMP.withZone(clock.getZone()).format(Instant.now(clock));
        Path base = logDir.resolve(timestamp + "-" + sessionId.value() + ".log");
        if (Files.notExists(base, LinkOption.NOFOLLOW_LINKS)) {
            return base;
        }
        int suffix = 1;
        while (true) {
            Path candidate = logDir.resolve(timestamp + "-" + sessionId.value() + "-" + suffix + ".log");
            if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
            suffix++;
        }
    }

    private void pruneOldSessionLogs(Path logDir, int maxFiles) throws IOException {
        if (maxFiles <= 0 || !Files.isDirectory(logDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> logFiles;
        try (Stream<Path> entries = Files.list(logDir)) {
            logFiles = entries
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> SESSION_LOG_FILE.matcher(path.getFileName().toString()).matches())
                .sorted(
                    Comparator.comparing(this::readModifiedTimeSafe)
                        .thenComparing(path -> path.getFileName().toString())
                )
                .toList();
        }
        int excess = logFiles.size() - maxFiles + 1;
        for (int i = 0; i < excess; i++) {
            Files.deleteIfExists(logFiles.get(i));
        }
    }

    private FileTime readModifiedTimeSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException _) {
            return FileTime.fromMillis(0L);
        }
    }

    private void applyPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException _) {
            // Non-POSIX filesystem; ignore gracefully
        }
    }

    private void openConsoleHandler(Logger rootLogger, Formatter formatter) {
        ConsoleHandler consoleHandler = new ConsoleHandler(Target.SYSTEM_ERR, formatter);
        try {
            consoleHandler.setEncoding(StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException _) {
            // Keep platform default encoding if UTF-8 encoding name is unsupported
        }
        consoleHandler.setLevel(ALL);
        rootLogger.addHandler(consoleHandler);
        activeHandlers.add(consoleHandler);
    }
}
