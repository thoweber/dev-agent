package guru.thomasweber.devagent.spi.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackendBuildSystemTest {

    @Test
    @DisplayName("Given default BackendBuildSystem interface method - when sanitizeOutput with exitCode called - then delegates to 2-arg sanitizeOutput")
    void givenDefaultBackendBuildSystemInterfaceMethod_whenCalled_thenDelegates() {
        BackendBuildSystem stub = new BackendBuildSystem() {
            @Override
            public int priority() {
                return 1;
            }

            @Override
            public boolean isApplicable(Path root) {
                return false;
            }

            @Override
            public String resolveExecutable(Path root) {
                return "mvn";
            }

            @Override
            public List<String> buildCompileCommand(Path root, String module, String target) {
                return List.of();
            }

            @Override
            public List<String> buildTestCommand(Path root, String module, String pattern, String target) {
                return List.of();
            }

            @Override
            public SanitizedLog sanitizeOutput(String out, String err) {
                return SanitizedLog.builder().cleanStdout(out).cleanStderr(err).build();
            }
        };

        SanitizedLog result = stub.sanitizeOutput(0, "stdout", "stderr");
        assertThat(result.cleanStdout()).isEqualTo("stdout");
        assertThat(result.cleanStderr()).isEqualTo("stderr");
    }
}
