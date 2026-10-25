package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIdResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given local directory without git remote - when resolveProjectId called - then returns SHA-256 fallback")
    void testResolveProjectIdFallback() {
        ProjectIdResolver resolver = new ProjectIdResolver();
        String projectId = resolver.resolveForPath(tempDir);

        assertThat(projectId).isNotNull().hasSize(64); // SHA-256 hex string length
    }

    @Test
    @DisplayName("Given git remote url - when normalized - then returns clean repo identifier")
    void testNormalizeRemoteUrl() {
        ProjectIdResolver resolver = new ProjectIdResolver();

        String gitUrl = "git@github.com:thoweber/dev-agent.git";
        assertThat(resolver.normalizeRemote(gitUrl)).isEqualTo("thoweber/dev-agent");

        String httpsUrl = "https://github.com/thoweber/dev-agent.git";
        assertThat(resolver.normalizeRemote(httpsUrl)).isEqualTo("thoweber/dev-agent");

        String plainHttps = "https://github.com/my-org/my-project";
        assertThat(resolver.normalizeRemote(plainHttps)).isEqualTo("my-org/my-project");
    }
}
