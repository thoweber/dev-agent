package guru.thomasweber.devagent.cognitive;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Declarative rule for matching and stemming suffix variations on words.
 */
public interface StemmableSuffix {

    /**
     * Suffix variations sharing the same length constraint and replacement semantics
     * (e.g. {@code ["ize", "ise"]}, {@code ["able", "ible"]}).
     */
    List<String> variations();

    /**
     * Minimum word length required for eligibility, or {@code -1} if no minimum exists.
     */
    int minWordSize();

    /**
     * Replacement string appended after stripping the matched suffix variation.
     * Defaults to empty string (complete removal).
     */
    default String replacement() {
        return "";
    }

    /**
     * Whether matching this rule preserves the word unchanged (e.g. protected endings).
     */
    default boolean preservesWord() {
        return false;
    }

    /**
     * Evaluates whether the given word matches any variation subject to minWordSize.
     * If matched, returns the stemmed word; otherwise returns {@link Optional#empty()}.
     */
    default Optional<String> stem(String word) {
        if (word == null || word.isEmpty() || (minWordSize() > 0 && word.length() < minWordSize())) {
            return Optional.empty();
        }
        for (String variation : variations()) {
            if (word.endsWith(variation)) {
                if (preservesWord()) {
                    return Optional.of(word);
                }
                String base = word.substring(0, word.length() - variation.length());
                return Optional.of(base + replacement());
            }
        }
        return Optional.empty();
    }

    static StemmableSuffix strip(int minWordSize, String... variations) {
        return new SuffixRule(List.of(variations), minWordSize, "", false);
    }

    static StemmableSuffix replace(String suffix, String replacement) {
        return new SuffixRule(List.of(suffix), -1, replacement, false);
    }

    static StemmableSuffix replace(int minWordSize, String replacement, String... variations) {
        return new SuffixRule(List.of(variations), minWordSize, replacement, false);
    }

    static StemmableSuffix preserve(String... variations) {
        return new SuffixRule(List.of(variations), -1, "", true);
    }

    /**
     * Immutable record implementing {@link StemmableSuffix}.
     */
    record SuffixRule(
            List<String> variations,
            int minWordSize,
            String replacement,
            boolean preservesWord
    ) implements StemmableSuffix {
        public SuffixRule {
            variations = List.copyOf(Objects.requireNonNull(variations, "variations must not be null"));
            replacement = replacement != null ? replacement : "";
        }
    }
}
