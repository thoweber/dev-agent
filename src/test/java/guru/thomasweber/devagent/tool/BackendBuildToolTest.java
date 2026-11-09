package guru.thomasweber.devagent.tool;

import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
