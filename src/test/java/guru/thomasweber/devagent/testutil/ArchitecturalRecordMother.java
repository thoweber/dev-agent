package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.RepoPath;

import java.nio.file.Path;
import java.util.List;

/**
 * Canonical test fixtures for {@link ArchitecturalRecord}.
 */
public final class ArchitecturalRecordMother {

    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"));

    private ArchitecturalRecordMother() {}

    private static RepoPath mockPath(String relativePath) {
        return RepoPath.of(ROOT.resolve(relativePath), ROOT);
    }

    public static ArchitecturalRecord adr001() {
        return ArchitecturalRecord.builder()
            .id("ADR-001")
            .title("ADR-001: Quarkus Picocli Native CLI")
            .status("Accepted")
            .tags(List.of("cli", "native", "picocli"))
            .path(mockPath("docs/adr/ADR-001-quarkus-picocli-native-cli.md"))
            .summary("Ahead-of-Time native binary targeting sub-50ms cold boot via GraalVM/Mandrel.")
            .content("""
                # ADR-001: Quarkus Picocli Native CLI
                * **Status:** Accepted
                Ahead-of-Time native binary targeting sub-50ms cold boot via GraalVM/Mandrel.
                """)
            .build();
    }

    public static ArchitecturalRecord adr003() {
        return ArchitecturalRecord.builder()
            .id("ADR-003")
            .title("ADR-003: Workspace Path Jail & Canonical RepoPath")
            .status("Accepted")
            .tags(List.of("security", "jail", "sandbox"))
            .path(mockPath("docs/adr/ADR-003-workspace-path-jail.md"))
            .summary("Canonical toRealPath() boundary containment and strongly-typed RepoPath value object.")
            .content("""
                # ADR-003: Workspace Path Jail & Canonical RepoPath
                * **Status:** Accepted
                Canonical toRealPath() boundary containment and strongly-typed RepoPath value object.
                """)
            .build();
    }

    public static ArchitecturalRecord adr005() {
        return ArchitecturalRecord.builder()
            .id("ADR-005")
            .title("ADR-005: Pure-Java BM25 Retrieval Engine")
            .status("Accepted")
            .tags(List.of("rag", "bm25", "native-safe"))
            .path(mockPath("docs/adr/ADR-005-pure-java-bm25-retrieval.md"))
            .summary("Zero-JNI lexical inverted index eliminating native C++ ONNX library failures.")
            .content("""
                # ADR-005: Pure-Java BM25 Retrieval Engine
                * **Status:** Accepted
                Zero-JNI lexical inverted index eliminating native C++ ONNX library failures.
                """)
            .build();
    }

    public static ArchitecturalRecord adr009() {
        return ArchitecturalRecord.builder()
            .id("ADR-009")
            .title("ADR-009: Deterministic Caffeine Cache & Normalized Loop Breaker")
            .status("Accepted")
            .tags(List.of("cache", "circuit-breaker", "recovery"))
            .path(mockPath("docs/adr/ADR-009-cache-and-circuit-breaker.md"))
            .summary("Caffeine gatekeeper memoization and normalized error loop circuit breaker.")
            .content("""
                # ADR-009: Deterministic Caffeine Cache & Normalized Loop Breaker
                * **Status:** Accepted
                Caffeine gatekeeper memoization and normalized error loop circuit breaker.
                """)
            .build();
    }

    public static ArchitecturalRecord rule01() {
        return ArchitecturalRecord.builder()
            .id("01-architecture-invariants")
            .title("Rule 01: Core Architecture Invariants")
            .status("Active")
            .tags(List.of("rules", "invariants"))
            .path(mockPath(".agents/rules/01-architecture-invariants.md"))
            .summary("Non-negotiable architectural invariants and engine vs knowledge separation.")
            .content("""
                # dev-agent Core Architecture Invariants
                Non-negotiable architectural invariants and engine vs knowledge separation.
                """)
            .build();
    }

    public static ArchitecturalRecord custom(String id, String title, String content) {
        return ArchitecturalRecord.builder()
            .id(id)
            .title(title)
            .status("Active")
            .tags(List.of("custom"))
            .path(mockPath("custom/" + id + ".md"))
            .summary(title)
            .content(content)
            .build();
    }
}
