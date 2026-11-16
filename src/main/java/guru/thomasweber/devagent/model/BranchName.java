package guru.thomasweber.devagent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;

/**
 * Strongly-typed, self-validating value object representing a safe Git branch name.
 * Prevents Git flag injection (leading hyphens), ref traversal ({@code ..}), and malformed refs.
 */
@RegisterForReflection
public record BranchName(@JsonValue String value) {

    public static final String DEFAULT_TEMPLATE = "feature/{task-id}-{story-slug}";
    private static final int MAX_SLUG_LENGTH = 48;
    private static final Pattern SAFE_BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9._/][A-Za-z0-9._/-]*$");

    public BranchName {
        if (isNull(value)
            || value.isBlank()
            || value.startsWith("-")
            || value.startsWith("/")
            || value.endsWith("/")
            || value.endsWith(".")
            || value.endsWith(".lock")
            || value.contains("..")
            || value.contains("//")
            || value.contains("@{")
            || !SAFE_BRANCH_PATTERN.matcher(value).matches()) {
            throw new SecurityException("Invalid or unsafe Git branch name: " + value);
        }
    }

    @JsonCreator
    public static BranchName of(String raw) {
        return new BranchName(raw);
    }

    public static BranchName forTask(TaskId taskId) {
        if (isNull(taskId)) {
            throw new SecurityException("TaskId must not be null when deriving branch name");
        }
        return new BranchName("feature/" + taskId.value());
    }

    public static BranchName render(String template, TaskId taskId, String storySlug) {
        if (isNull(taskId)) {
            throw new SecurityException("TaskId must not be null when rendering branch name");
        }
        String effectiveTemplate = DEFAULT_TEMPLATE;
        if (!isNull(template) && !template.isBlank()) {
            effectiveTemplate = template.trim();
        }
        String rendered = effectiveTemplate
            .replace("{task-id}", taskId.value())
            .replace("{story-slug}", sanitizeSlug(storySlug));
        return new BranchName(rendered);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String sanitizeSlug(String storySlug) {
        if (isNull(storySlug) || storySlug.isBlank()) {
            return "task";
        }
        String normalized = storySlug.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) {
            return "task";
        }
        if (normalized.length() > MAX_SLUG_LENGTH) {
            return normalized.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        return normalized;
    }
}
