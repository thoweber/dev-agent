package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import org.quarkus.agent.DevAgentCommand;
import org.quarkus.agent.workspace.WorkspaceJailValidator;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Dependent
@Unremovable
@Command(
    name = "init",
    description = "Inspects the workspace and initializes declarative .agents configuration",
    mixinStandardHelpOptions = true
)
@SuppressWarnings("java:S106")
public class InitCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @ParentCommand
    private DevAgentCommand parentCommand;

    @Inject
    private WorkspaceJailValidator jailValidator;

    @Option(names = {"-d", "--dir", "--workspace"}, description = "Target workspace root directory", defaultValue = ".")
    private String dir = ".";

    @Option(names = {"-t", "--type"}, description = "Archetype profile (rest-panache, reactive-messaging, fullstack-angular, modulith)", defaultValue = "rest-panache")
    private String type = "rest-panache";

    @Option(names = {"--force"}, description = "Force overwrite existing configuration files")
    private boolean force;

    public InitCommand() {
    }

    public InitCommand(WorkspaceJailValidator jailValidator) {
        this.jailValidator = jailValidator;
    }

    @Override
    public void run() {
        ArchetypeType archetype = ArchetypeType.fromString(type);
        Path targetRoot = Paths.get(getDir()).toAbsolutePath().normalize();
        Path rawYamlPath = targetRoot.resolve(".agents/dev-agent.yaml");
        if (Files.isSymbolicLink(rawYamlPath)) {
            throw new SecurityException("Refusing to write through symbolic link: " + rawYamlPath);
        }

        WorkspaceJailValidator effectiveJail = jailValidator != null ? jailValidator : new WorkspaceJailValidator(targetRoot);

        Path agentsDir = effectiveJail.validateAndResolve(".agents");
        Path rulesDir = effectiveJail.validateAndResolve(".agents/rules");
        Path yamlPath = effectiveJail.validateForWrite(".agents/dev-agent.yaml");

        try {
            if (!Files.exists(agentsDir)) {
                Files.createDirectories(agentsDir);
            }
            if (!Files.exists(rulesDir)) {
                Files.createDirectories(rulesDir);
            }

            if (Files.exists(yamlPath) && !force) {
                String existingYaml = Files.readString(yamlPath);
                if (!existingYaml.contains("Archetype: " + archetype.getId())) {
                    getErr().printf("[dev-agent] Workspace already initialized with different archetype. Use --force to overwrite.%n");
                    return;
                }
            } else {
                String yamlContent = generateYamlConfig(archetype);
                Files.writeString(yamlPath, yamlContent);
            }

            scaffoldArchetypeRules(rulesDir, archetype);

            getOut().printf("[dev-agent] Initialized workspace configuration for archetype '%s' in .agents/%n", archetype.getId());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scaffold .agents configuration", e);
        }
    }

    private PrintWriter getOut() {
        return spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    }

    private PrintWriter getErr() {
        return spec != null ? spec.commandLine().getErr() : new PrintWriter(System.err, true);
    }

    public String getDir() {
        if (dir != null && !dir.equals(".") && !dir.isBlank()) {
            return dir;
        }
        if (parentCommand != null && parentCommand.getDir() != null) {
            return parentCommand.getDir();
        }
        return dir != null ? dir : ".";
    }

    private String generateYamlConfig(ArchetypeType archetype) {
        return String.format("""
            # ==============================================================================
            # Quarkus Dev-Agent Declarative Configuration (.agents/dev-agent.yaml)
            # Archetype: %s
            # ==============================================================================

            agent:
              workspace:
                backend-root: "."
              build:
                backend:
                  target-dir: "target-agent"
                  dev-services:
                    enabled: false
              contract:
                enabled: %b
              knowledge:
                rules-paths:
                  - ".agents/rules/**/*.md"
                  - "docs/adr/*.md"
                retrieval:
                  engine: "bm25"
                  max-adrs-per-prompt: 4
              isolation:
                use-worktrees: true
                branch-template: "feature/{task-id}-{story-slug}"
              budget:
                max-task-cost-usd: 2.00
                max-task-tokens: 250000
            """, archetype.getId(), archetype == ArchetypeType.FULLSTACK_ANGULAR);
    }

    private void scaffoldArchetypeRules(Path rulesDir, ArchetypeType archetype) throws IOException {
        switch (archetype) {
            case REST_PANACHE -> {
                writeRuleIfMissing(rulesDir.resolve("01-quarkus-panache-invariants.md"), """
                    # Rule: Quarkus Panache & Persistence Best Practices
                    * Use Active Record or Repository pattern consistently.
                    * In micro-TDD loops, ensure dev-services are suppressed in favor of in-memory H2.
                    """);
                writeRuleIfMissing(rulesDir.resolve("02-rest-validation-rules.md"), """
                    # Rule: REST API & DTO Invariants
                    * All endpoint inputs must have Jakarta Validation annotations (@NotNull, @Valid, @Size).
                    * Return Response.created() or Response.ok() with explicit entity DTOs.
                    """);
            }
            case REACTIVE_MESSAGING -> writeRuleIfMissing(rulesDir.resolve("01-reactive-messaging-invariants.md"), """
                # Rule: Reactive Messaging & Mutiny Streams
                * Never block Vert.x event loops. Use @Blocking only when invoking legacy blocking I/O.
                * Prefer Uni and Multi types for reactive asynchronous pipelines.
                """);
            case FULLSTACK_ANGULAR -> {
                writeRuleIfMissing(rulesDir.resolve("01-angular-standalone-signals.md"), """
                    # Rule: Angular Standalone & Signals Reactivity
                    * Components must be standalone: standalone: true.
                    * Bridge OpenAPI RxJS observables to Signals using toSignal() from @angular/core/rxjs-interop.
                    """);
                writeRuleIfMissing(rulesDir.resolve("02-contract-sync.md"), """
                    # Rule: Contract Synchronization
                    * Generated TypeScript API clients reside strictly in frontend/src/app/core/api/generated/.
                    * Never commit manual modifications directly inside generated client directories.
                    """);
            }
            case MODULITH -> writeRuleIfMissing(rulesDir.resolve("01-modulith-package-boundaries.md"), """
                # Rule: Modulith Package Boundaries
                * Enforce hexagonal isolation between module packages.
                * Cross-module communication must proceed through published events or SPI interfaces.
                """);
        }
    }

    private void writeRuleIfMissing(Path path, String content) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new SecurityException("Refusing to write through symbolic link: " + path);
        }
        if (!Files.exists(path) || force) {
            Files.writeString(path, content.stripIndent());
        }
    }
}
