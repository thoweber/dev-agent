package guru.thomasweber.devagent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendBuildToolTest {

    @Test
    @DisplayName("Given BackendBuildTool - when compileBackend called - then delegates to BackendBuildService")
    void givenBackendBuildTool_whenCompileBackendCalled_thenDelegatesToBackendBuildService() {
        BackendBuildService service = mock(BackendBuildService.class);
        SanitizedLog expected = SanitizedLog.builder().exitCode(0).build();
        when(service.compile("core-module")).thenReturn(expected);

        BackendBuildTool tool = new BackendBuildTool(service);
        SanitizedLog result = tool.compileBackend("core-module");

        assertThat(result).isEqualTo(expected);
        verify(service).compile("core-module");
    }

    @Test
    @DisplayName("Given BackendBuildTool - when runBackendTest called - then delegates to BackendBuildService")
    void givenBackendBuildTool_whenRunBackendTestCalled_thenDelegatesToBackendBuildService() {
        BackendBuildService service = mock(BackendBuildService.class);
        SanitizedLog expected = SanitizedLog.builder().exitCode(0).build();
        when(service.test("OrderServiceTest", "services/order-service")).thenReturn(expected);

        BackendBuildTool tool = new BackendBuildTool(service);
        SanitizedLog result = tool.runBackendTest("OrderServiceTest", "services/order-service");

        assertThat(result).isEqualTo(expected);
        verify(service).test("OrderServiceTest", "services/order-service");
    }

    @Test
    @DisplayName("Given tool methods - when inspected - then have Tool and P annotations")
    void givenToolMethods_whenInspected_thenHaveToolAndPAnnotations() throws NoSuchMethodException {
        Method compileMethod = BackendBuildTool.class.getMethod("compileBackend", String.class);
        assertThat(compileMethod.isAnnotationPresent(Tool.class)).isTrue();
        Tool compileTool = compileMethod.getAnnotation(Tool.class);
        assertThat(compileTool.value()[0]).contains("Compiles");

        Parameter[] compileParams = compileMethod.getParameters();
        assertThat(compileParams[0].isAnnotationPresent(P.class)).isTrue();

        Method testMethod = BackendBuildTool.class.getMethod("runBackendTest", String.class, String.class);
        assertThat(testMethod.isAnnotationPresent(Tool.class)).isTrue();
        Tool testTool = testMethod.getAnnotation(Tool.class);
        assertThat(testTool.value()[0]).contains("tests");

        Parameter[] testParams = testMethod.getParameters();
        assertThat(testParams[0].isAnnotationPresent(P.class)).isTrue();
        assertThat(testParams[1].isAnnotationPresent(P.class)).isTrue();
    }
}
