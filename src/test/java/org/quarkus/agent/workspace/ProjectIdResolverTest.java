package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quarkus.agent.model.ProjectId;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIdResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given local directory without git remote - when resolveProjectId called - then returns deterministic UUIDv3 ProjectId")
    void testResolveProjectIdFallback() throws IOException {
        ProjectIdResolver resolver = new ProjectIdResolver();
        ProjectId projectId = resolver.resolveForPath(tempDir);

        assertThat(projectId).isNotNull();
        assertThat(projectId.value().version()).isEqualTo(3);
        assertThat(projectId).isEqualTo(ProjectId.fromCoordinate("local:" + tempDir.toRealPath()));
    }

    @Test
    @DisplayName("Given git remote url across protocols - when normalized - then scrubs credentials, ports and extracts host and repo identifier")
    void testNormalizeRemoteUrlProtocols() {
        ProjectIdResolver resolver = new ProjectIdResolver();

        // SCP syntax
        assertThat(resolver.normalizeRemote("git@github.com:thoweber/dev-agent.git"))
            .isEqualTo("github.com/thoweber/dev-agent");

        // HTTPS standard
        assertThat(resolver.normalizeRemote("https://github.com/thoweber/dev-agent.git"))
            .isEqualTo("github.com/thoweber/dev-agent");

        // HTTPS with plain token
        assertThat(resolver.normalizeRemote("https://oauth2:ghp_secretToken123@github.com/thoweber/dev-agent.git"))
            .isEqualTo("github.com/thoweber/dev-agent");

        // HTTPS with user and password on subgroup
        assertThat(resolver.normalizeRemote("https://user:pass123@gitlab.com/group/subgroup/project.git"))
            .isEqualTo("gitlab.com/group/subgroup/project");

        // SSH with protocol and port
        assertThat(resolver.normalizeRemote("ssh://git@github.com:22/thoweber/dev-agent.git"))
            .isEqualTo("github.com/thoweber/dev-agent");

        // SSH with protocol without port
        assertThat(resolver.normalizeRemote("ssh://git@gitlab.internal.corp/team/project.git"))
            .isEqualTo("gitlab.internal.corp/team/project");

        // Git protocol
        assertThat(resolver.normalizeRemote("git://github.com/thoweber/dev-agent.git"))
            .isEqualTo("github.com/thoweber/dev-agent");

        // Host case insensitivity
        assertThat(resolver.normalizeRemote("HTTPS://GITHUB.COM/thoweber/dev-agent.git"))
            .isEqualTo("github.com/thoweber/dev-agent");

        // Trailing slash
        assertThat(resolver.normalizeRemote("https://github.com/thoweber/dev-agent/"))
            .isEqualTo("github.com/thoweber/dev-agent");
    }

    @Test
    @DisplayName("Given null or blank remote - returns unknown")
    void testNormalizeNullAndBlank() {
        ProjectIdResolver resolver = new ProjectIdResolver();
        assertThat(resolver.normalizeRemote(null)).isEqualTo("unknown");
        assertThat(resolver.normalizeRemote("   ")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("Given resolver initialized with workspace - resolves ProjectId")
    void testResolveProjectIdWithWorkspace() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        ProjectIdResolver resolver = new ProjectIdResolver(workspace);

        ProjectId projectId = resolver.resolveProjectId();
        assertThat(projectId).isNotNull();
        assertThat(projectId.value().version()).isEqualTo(3);
        assertThat(projectId).isEqualTo(ProjectId.fromCoordinate("local:" + tempDir.toRealPath()));
    }

    @Test
    @DisplayName("Given same repository accessed via different protocols or credentials - produces identical ProjectId")
    void testCredentialAndProtocolEquivalence() {
        ProjectIdResolver resolver = new ProjectIdResolver();

        String sshUrl = "git@github.com:thoweber/dev-agent.git";
        String httpsWithToken = "https://oauth2:secretToken@github.com/thoweber/dev-agent.git";
        String sshWithPort = "ssh://git@github.com:22/thoweber/dev-agent.git";

        ProjectId idSsh = ProjectId.fromCoordinate(resolver.normalizeRemote(sshUrl));
        ProjectId idHttps = ProjectId.fromCoordinate(resolver.normalizeRemote(httpsWithToken));
        ProjectId idPort = ProjectId.fromCoordinate(resolver.normalizeRemote(sshWithPort));

        assertThat(idSsh).isEqualTo(idHttps).isEqualTo(idPort);
    }
}
