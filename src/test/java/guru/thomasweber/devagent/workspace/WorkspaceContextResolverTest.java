package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.spi.build.BackendBuildSystem;
import guru.thomasweber.devagent.spi.build.MavenBuildSystem;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    @DisplayName("Given multi-module git repository - when resolveRepoRoot called from submodule - then prefers git repository root over submodule pom")
    void testResolveRepoRootMultiModuleGitRepoPrefersGitRootOverSubmodulePom() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        Files.createFile(tempDir.resolve("pom.xml"));
        Path subModuleDir = Files.createDirectories(tempDir.resolve("services/order-service"));
        Files.createFile(subModuleDir.resolve("pom.xml"));
        Path deepSrcDir = Files.createDirectories(subModuleDir.resolve("src/main/java"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(deepSrcDir);

        assertThat(resolver.resolveRepoRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Given multi-module non-git workspace - when resolveRepoRoot called from submodule - then prefers highest ancestor build root")
    void testResolveRepoRootMultiModuleNonGitPrefersHighestBuildRoot() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        Path subModuleDir = Files.createDirectories(tempDir.resolve("submodule"));
        Files.createFile(subModuleDir.resolve("pom.xml"));
        Path deepSrcDir = Files.createDirectories(subModuleDir.resolve("src/test"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(deepSrcDir);

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

    @Test
    @DisplayName("Given project with pom.xml - when resolveBackendBuildSystem called - then discovers MavenBuildSystem")
    @SuppressWarnings("unchecked")
    void testResolveBackendBuildSystemWithPomXml() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));

        MavenBuildSystem maven = new MavenBuildSystem();
        Instance<BackendBuildSystem> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.stream()).thenAnswer(_ -> Stream.of(maven));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir, instance);
        Optional<BackendBuildSystem> buildSystem = resolver.resolveBackendBuildSystem();

        assertThat(buildSystem).contains(maven);
    }

    @Test
    @DisplayName("Given null, unresolvable instance, or null project root - when resolveBackendBuildSystem called - then returns empty")
    @SuppressWarnings("unchecked")
    void testResolveBackendBuildSystemUnresolvable() {
        WorkspaceContextResolver resolverNull = new WorkspaceContextResolver(tempDir, null);
        assertThat(resolverNull.resolveBackendBuildSystem()).isEmpty();

        Instance<BackendBuildSystem> unsatisfied = mock(Instance.class);
        when(unsatisfied.isUnsatisfied()).thenReturn(true);

        WorkspaceContextResolver resolverUnsatisfied = new WorkspaceContextResolver(tempDir, unsatisfied);
        assertThat(resolverUnsatisfied.resolveBackendBuildSystem()).isEmpty();
        assertThat(resolverUnsatisfied.resolveBackendBuildSystem(null)).isEmpty();
    }

    @Test
    @DisplayName("Given multiple applicable build systems - when resolveBackendBuildSystem called - then selects highest priority")
    @SuppressWarnings("unchecked")
    void testResolveBackendBuildSystemSelectsHighestPriority() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));

        BackendBuildSystem lowPriority = mock(BackendBuildSystem.class);
        when(lowPriority.isApplicable(tempDir)).thenReturn(true);
        when(lowPriority.priority()).thenReturn(50);

        BackendBuildSystem highPriority = mock(BackendBuildSystem.class);
        when(highPriority.isApplicable(tempDir)).thenReturn(true);
        when(highPriority.priority()).thenReturn(100);

        Instance<BackendBuildSystem> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.stream()).thenAnswer(_ -> Stream.of(lowPriority, highPriority));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir, instance);
        Optional<BackendBuildSystem> selected = resolver.resolveBackendBuildSystem(tempDir);

        assertThat(selected).contains(highPriority);
    }

    @Test
    @DisplayName("Given CDI constructor with Instance - when instantiated - then initializes with user.dir")
    @SuppressWarnings("unchecked")
    void testCdiConstructor() {
        Instance<BackendBuildSystem> instance = mock(Instance.class);
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(instance);
        assertThat(resolver.getWorkingDir()).isEqualTo(Path.of(System.getProperty("user.dir")));
        assertThat(resolver.getProjectRoot()).isNotNull();
    }

    @Test
    @DisplayName("Given workspace resolver - when getProjectRoot called - then delegates to resolveRepoRoot")
    void testGetProjectRoot() {
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);
        assertThat(resolver.getProjectRoot()).isEqualTo(resolver.resolveRepoRoot());
    }

    @Test
    @DisplayName("Given frontend dir with angular.json only - when resolveFrontendRoot called - then discovers frontend root")
    void testResolveFrontendRootWithAngularJsonOnly() throws IOException {
        Path frontendDir = Files.createDirectories(tempDir.resolve("frontend"));
        Files.createFile(frontendDir.resolve("angular.json"));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(tempDir);
        assertThat(resolver.resolveFrontendRoot()).contains(frontendDir);
    }
}
