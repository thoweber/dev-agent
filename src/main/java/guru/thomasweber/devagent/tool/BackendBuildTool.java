package guru.thomasweber.devagent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Driving adapter exposing backend build and test capabilities as LangChain4j tools.
 * Exposes clean, high-level intent APIs to AI personas while delegating orchestration
 * and subprocess execution to BackendBuildService.
 */
@ApplicationScoped
public class BackendBuildTool {

    private final BackendBuildService buildService;

    @Inject
    public BackendBuildTool(BackendBuildService buildService) {
        this.buildService = buildService;
    }

    /**
     * Compiles the backend project and returns compiler diagnostic errors.
     *
     * @param modulePath optional relative submodule path within multi-module reactor, or empty/null for root module
     * @return SanitizedLog containing exit code, clean output, and extracted compiler error lines
     */
    @Tool("Compiles the backend project and returns compiler diagnostic errors.")
    public SanitizedLog compileBackend(
        @P("Relative submodule path within multi-module reactor, or empty/null for root module") String modulePath
    ) {
        return buildService.compile(modulePath);
    }

    /**
     * Executes backend unit and integration tests and returns failure stack traces and assertion diffs.
     *
     * @param testPattern test pattern or class name to execute, e.g. 'OrderServiceTest' or '*Test'
     * @param modulePath optional relative submodule path within multi-module reactor, or empty/null for root module
     * @return SanitizedLog containing exit code, clean output, and extracted failure causes
     */
    @Tool("Executes backend unit and integration tests and returns failure stack traces and assertion diffs.")
    public SanitizedLog runBackendTest(
        @P("Test pattern or class name to execute, e.g. 'OrderServiceTest' or '*Test'") String testPattern,
        @P("Relative submodule path within multi-module reactor, or empty/null for root module") String modulePath
    ) {
        return buildService.test(testPattern, modulePath);
    }
}
