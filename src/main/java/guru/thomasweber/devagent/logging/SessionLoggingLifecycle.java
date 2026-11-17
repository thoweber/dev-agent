package guru.thomasweber.devagent.logging;

import guru.thomasweber.devagent.DevAgentCommand;
import guru.thomasweber.devagent.cli.BaseAgentCommand;
import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.SessionLoggingRequest;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import picocli.CommandLine.ParseResult;

import java.nio.file.Path;
import java.util.Objects;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Hooks Picocli command execution and Quarkus shutdown events to configure per-session logging.
 */
@ApplicationScoped
public class SessionLoggingLifecycle {

    private static final Logger LOG = Logger.getLogger(SessionLoggingLifecycle.class);

    private final SessionLoggingService loggingService;
    private final DevAgentConfigLoader configLoader;

    @Inject
    public SessionLoggingLifecycle(SessionLoggingService loggingService, DevAgentConfigLoader configLoader) {
        this.loggingService = Objects.requireNonNullElseGet(loggingService, SessionLoggingService::new);
        this.configLoader = Objects.requireNonNullElseGet(configLoader, DevAgentConfigLoader::new);
    }

    public SessionLoggingLifecycle() {
        this(new SessionLoggingService(), new DevAgentConfigLoader());
    }

    public void onParseResult(@Observes ParseResult parseResult) {
        if (isNull(parseResult)) {
            return;
        }
        ParseResult leaf = resolveLeafParseResult(parseResult);
        int cliVerbosity = resolveCliVerbosity(parseResult);
        Path repoRoot = resolveRepoRoot(parseResult, leaf);
        LoadOutcome configOutcome = loadConfigSafely(repoRoot);
        String commandName = leaf.commandSpec().name();

        loggingService.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(repoRoot)
                .cliVerbosityCount(cliVerbosity)
                .loggingConfig(configOutcome.config().logging())
                .commandName(commandName)
                .build()
        );
        if (nonNull(configOutcome.warning())) {
            LOG.warnf("Using default configuration due to invalid .agents/dev-agent.yaml: %s", configOutcome.warning());
        }
    }

    public void onShutdown(@Observes ShutdownEvent event) {
        loggingService.close();
    }

    private ParseResult resolveLeafParseResult(ParseResult parseResult) {
        ParseResult current = parseResult;
        while (current.hasSubcommand()) {
            current = current.subcommand();
        }
        return current;
    }

    private int resolveCliVerbosity(ParseResult parseResult) {
        Object rootObj = parseResult.commandSpec().root().userObject();
        if (rootObj instanceof DevAgentCommand devAgentCommand) {
            return devAgentCommand.getVerbosityCount();
        }
        return 0;
    }

    private Path resolveRepoRoot(ParseResult parseResult, ParseResult leaf) {
        Object leafObj = leaf.commandSpec().userObject();
        if (leafObj instanceof BaseAgentCommand baseCommand) {
            try {
                return baseCommand.getWorkspaceContextResolver().resolveRepoRoot();
            } catch (RuntimeException _) {
                return baseCommand.getWorkspaceRoot();
            }
        }
        Object rootObj = parseResult.commandSpec().root().userObject();
        if (rootObj instanceof DevAgentCommand devAgentCommand) {
            Path rawDir = Path.of(Objects.requireNonNullElse(devAgentCommand.getDir(), "."));
            return new WorkspaceContextResolver(rawDir).resolveRepoRoot();
        }
        return new WorkspaceContextResolver(Path.of(".")).resolveRepoRoot();
    }

    private LoadOutcome loadConfigSafely(Path repoRoot) {
        try {
            return new LoadOutcome(configLoader.loadForWorkspace(repoRoot), null);
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            return new LoadOutcome(configLoader.loadFromYaml(""), e.getMessage());
        }
    }

    private record LoadOutcome(DevAgentConfig config, String warning) {}
}
