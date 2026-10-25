# Mermaid Diagram Templates for dev-agent

Use these clean templates when visualizing architecture, workflows, and state transitions.

## 1. Flowchart / Architecture Topology (`flowchart TD` / `flowchart LR`)

```mermaid
flowchart TD
    subgraph HostRuntime ["Host Runtime & CLI Layer"]
        CLI["Picocli CLI Engine (dev-agent)"]
        RES["WorkspaceContextResolver"]
        JAIL["WorkspaceJailValidator"]
    end

    subgraph SPIExtensibility ["Two-Axis Extensibility SPIs"]
        BS["BackendBuildSystem / FrontendBuildSystem"]
        FC["BackendFrameworkConvention / FrontendFrameworkConvention"]
    end

    subgraph Cognitive ["Cognitive Orchestration (LangChain4j)"]
        PLAN["FullStackPlannerAiService"]
        GATE["UniversalArchitectGatekeeperAiService"]
        CODE["FullStackCodeSynthesizerAiService"]
    end

    CLI --> RES --> JAIL
    JAIL --> BS
    JAIL --> FC
    BS --> CODE
    GATE --> CODE
```

## 2. Sequence Diagram (`sequenceDiagram`)

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer
    participant CLI as dev-agent CLI
    participant WT as GitWorktreeService
    participant Coder as FullStackCodeSynthesizer
    participant Gate as ArchitectGatekeeper

    Dev->>CLI: dev-agent feature "Implement Feature"
    CLI->>WT: allocateWorktree(taskId)
    WT-->>CLI: worktreePath (.git/dev-agent-worktrees/<id>)
    loop TDD Red-Green Loop
        CLI->>Coder: executeImplementationStep(instruction)
        Coder->>Coder: write failing test & patch production code
        Coder->>Gate: evaluate(diff)
        Gate-->>Coder: GateVerdict(approved=true)
    end
    CLI->>WT: commitAndSync()
    CLI-->>Dev: Feature Completed
```

## 3. State Machine (`stateDiagram-v2`)

```mermaid
stateDiagram-v2
    [*] --> ALLOCATING
    ALLOCATING --> PLANNING
    PLANNING --> IN_PROGRESS
    IN_PROGRESS --> GATE_AUDIT
    GATE_AUDIT --> IN_PROGRESS: violations detected (re-try)
    GATE_AUDIT --> SUCCESS: approved
    IN_PROGRESS --> CIRCUIT_BROKEN: 3 retries exceeded
    CIRCUIT_BROKEN --> ROLLED_BACK
    ROLLED_BACK --> [*]
    SUCCESS --> [*]
```
