package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceContextResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given project root with pom.xml - when resolveRepoRoot called - then discovers root")
    void testResolveRepoRootWithPomXml() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        Path subDir = Files.createDirectories(tempDir.resolve("src/main/java"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(subDir);

        assertThat(resolver.resolveRepoRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(resolver.getWorkingDir()).isEqualTo(subDir);
    }

    @Test
    @DisplayName("Given project root with .git - when resolveRepoRoot called - then discovers root")
    void testResolveRepoRootWithGit() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        Path subDir = Files.createDirectories(tempDir.resolve("sub/module"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(subDir);

        assertThat(resolver.resolveRepoRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Given project root with build.gradle - when resolveRepoRoot called - then discovers root")
    void testResolveRepoRootWithGradle() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle"));
        Path subDir = Files.createDirectories(tempDir.resolve("app"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(subDir);

        assertThat(resolver.resolveRepoRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Given project root with build.gradle.kts - when resolveRepoRoot called - then discovers root")
    void testResolveRepoRootWithGradleKts() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));
        Path subDir = Files.createDirectories(tempDir.resolve("app"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(subDir);

        assertThat(resolver.resolveRepoRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Given directory without repo markers - when resolveRepoRoot called - then falls back to workingDir")
    void testResolveRepoRootFallback() {
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);

        assertThat(resolver.resolveRepoRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Given frontend candidate directory with package.json - when resolveFrontendRoot called - then discovers frontend")
    void testResolveFrontendRoot() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        Path frontendDir = Files.createDirectories(tempDir.resolve("frontend"));
        Files.createFile(frontendDir.resolve("package.json"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);
        Optional<Path> frontend = resolver.resolveFrontendRoot();

        assertThat(frontend).contains(frontendDir);
    }

    @Test
    @DisplayName("Given ui directory with angular.json - when resolveFrontendRoot called - then discovers frontend")
    void testResolveFrontendRootWithAngularJson() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        Path uiDir = Files.createDirectories(tempDir.resolve("ui"));
        Files.createFile(uiDir.resolve("angular.json"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);
        Optional<Path> frontend = resolver.resolveFrontendRoot();

        assertThat(frontend).contains(uiDir);
    }

    @Test
    @DisplayName("Given no frontend directory - when resolveFrontendRoot called - then returns empty")
    void testResolveFrontendRootEmpty() {
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);

        assertThat(resolver.resolveFrontendRoot()).isEmpty();
    }

    @Test
    @DisplayName("Given workspace - when resolveAgentTargetDir called - then returns target-agent path")
    void testResolveAgentTargetDir() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);

        Path agentTarget = resolver.resolveAgentTargetDir();

        assertThat(agentTarget).isEqualTo(tempDir.toAbsolutePath().normalize().resolve("target-agent"));
    }

    @Test
    @DisplayName("Given default constructor - when initialized - uses user.dir")
    void testDefaultConstructor() {
        WorkspaceContextResolver resolver = new WorkspaceContextResolver();

        assertThat(resolver.getWorkingDir()).isEqualTo(Path.of(System.getProperty("user.dir")));
    }

    @Test
    @DisplayName("Given resolveEffectiveRoot - when called with various inputs - resolves correct root")
    void testResolveEffectiveRoot() {
        Path customDir = tempDir.resolve("custom");
        Path parentDir = tempDir.resolve("parent");

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot(customDir.toString(), parentDir.toString()))
            .isEqualTo(customDir.toAbsolutePath().normalize());

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot(".", parentDir.toString()))
            .isEqualTo(parentDir.toAbsolutePath().normalize());

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot(null, parentDir.toString()))
            .isEqualTo(parentDir.toAbsolutePath().normalize());

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot(".", "."))
            .isEqualTo(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot("   ", "   "))
            .isEqualTo(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot(null, null))
            .isEqualTo(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot("   ", parentDir.toString()))
            .isEqualTo(parentDir.toAbsolutePath().normalize());

        assertThat(WorkspaceContextResolver.resolveEffectiveRoot(".", null))
            .isEqualTo(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Given workspace - when resolving metadata paths - returns expected locations")
    void testResolveMetadataPaths() {
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);

        Path root = tempDir.toAbsolutePath().normalize();
        assertThat(resolver.resolveAgentsDir()).isEqualTo(root.resolve(".agents"));
        assertThat(resolver.resolveRulesDir()).isEqualTo(root.resolve(".agents/rules"));
        assertThat(resolver.resolveConfigFile()).isEqualTo(root.resolve(".agents/dev-agent.yaml"));
        assertThat(resolver.getJailValidator().getActiveWorkingRoot()).isEqualTo(root);
    }
}
