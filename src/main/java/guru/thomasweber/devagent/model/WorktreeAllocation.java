package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.nio.file.Path;

/**
 * Metadata describing an allocated isolated Git worktree for a task execution run.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record WorktreeAllocation(
    String taskId,
    String branch,
    Path worktreePath,
    String baseCommit,
    String checkpointStash,
    boolean hydrated
) {}
