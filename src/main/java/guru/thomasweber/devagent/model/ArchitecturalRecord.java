package guru.thomasweber.devagent.model;

import guru.thomasweber.devagent.workspace.RepoPath;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.util.List;
import java.util.Objects;

/**
 * Immutable representation of an architectural decision record or declarative rule document.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record ArchitecturalRecord(
    String id,
    String title,
    String status,
    List<String> tags,
    RepoPath path,
    String summary,
    String content
) {
    public ArchitecturalRecord {
        tags = List.copyOf(Objects.requireNonNullElse(tags, List.of()));
    }
}
