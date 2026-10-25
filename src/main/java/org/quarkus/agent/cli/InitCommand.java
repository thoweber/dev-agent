package org.quarkus.agent.cli;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.quarkus.agent.workspace.WorkspaceJailValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Dependent
@Unremovable
@Command(
    name = "init",
    description = "Inspects the workspace and initializes declarative .agents configuration",
    mixinStandardHelpOptions = true
)
public class InitCommand extends BaseAgentCommand {

    @Option(names = {"-t", "--type"}, description = "Archetype profile (rest-panache, reactive-messaging, fullstack-angular, modulith)", defaultValue = "rest-panache")
    private String type = "rest-panache";

    @Option(names = {"--force"}, description = "Force overwrite existing configuration files")
    private boolean force;

    @Override
    public void run() {
        ArchetypeType archetype = ArchetypeType.fromString(type);
        Path targetRoot = getWorkspaceRoot();
        WorkspaceJailValidator jail = new WorkspaceJailValidator(targetRoot);

        Path agentsDir = jail.validateAndResolve(".agents");
        Path rulesDir = jail.validateAndResolve(".agents/rules");
        Path yamlPath = jail.validateForWrite(".agents/dev-agent.yaml");

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

            scaffoldArchetypeRules(rulesDir, archetype, jail);

            getOut().printf("[dev-agent] Initialized workspace configuration for archetype '%s' in .agents/%n", archetype.getId());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scaffold .agents configuration", e);
        }
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

    private void scaffoldArchetypeRules(Path rulesDir, ArchetypeType archetype, WorkspaceJailValidator jail) throws IOException {
        switch (archetype) {
            case REST_PANACHE -> {
                writeRuleIfMissing(rulesDir.resolve("01-quarkus-panache-invariants.md"), """
                    # Rule: Quarkus Panache & Persistence Best Practices
                    * Use Active Record or Repository pattern consistently.
                    * In micro-TDD loops, ensure dev-services are suppressed in favor of in-memory H2.
                    """, jail);
                writeRuleIfMissing(rulesDir.resolve("02-rest-validation-rules.md"), """
                    # Rule: REST API & DTO Invariants
                    * All endpoint inputs must have Jakarta Validation annotations (@NotNull, @Valid, @Size).
                    * Return Response.created() or Response.ok() with explicit entity DTOs.
                    """, jail);
            }
            case REACTIVE_MESSAGING -> writeRuleIfMissing(rulesDir.resolve("01-reactive-messaging-invariants.md"), """
                # Rule: Reactive Messaging & Mutiny Streams
                * Never block Vert.x event loops. Use @Blocking only when invoking legacy blocking I/O.
                * Prefer Uni and Multi types for reactive asynchronous pipelines.
                """, jail);
            case FULLSTACK_ANGULAR -> {
                writeRuleIfMissing(rulesDir.resolve("01-angular-standalone-signals.md"), """
                    # Rule: Angular Standalone & Signals Reactivity
                    * Components must be standalone: standalone: true.
                    * Bridge OpenAPI RxJS observables to Signals using toSignal() from @angular/core/rxjs-interop.
                    """, jail);
                writeRuleIfMissing(rulesDir.resolve("02-contract-sync.md"), """
                    # Rule: Contract Synchronization
                    * Generated TypeScript API clients reside strictly in frontend/src/app/core/api/generated/.
                    * Never commit manual modifications directly inside generated client directories.
                    """, jail);
            }
            case MODULITH -> writeRuleIfMissing(rulesDir.resolve("01-modulith-package-boundaries.md"), """
                # Rule: Modulith Package Boundaries
                * Enforce hexagonal isolation between module packages.
                * Cross-module communication must proceed through published events or SPI interfaces.
                """, jail);
        }
    }

    private void writeRuleIfMissing(Path path, String content, WorkspaceJailValidator jail) throws IOException {
        jail.validateForWrite(path.toString());
        if (!Files.exists(path) || force) {
            Files.writeString(path, content.stripIndent());
        }
    }
}
