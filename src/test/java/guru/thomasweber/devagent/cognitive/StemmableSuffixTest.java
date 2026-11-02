package guru.thomasweber.devagent.cognitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StemmableSuffixTest {

    @Test
    @DisplayName("Given strip rule with single variation - when word meets min size - then suffix is stripped")
    void givenStripRule_whenWordMeetsMinSize_thenSuffixStripped() {
        StemmableSuffix rule = StemmableSuffix.strip(6, "ing");

        Optional<String> result = rule.stem("running");

        assertThat(result).contains("runn");
    }

    @Test
    @DisplayName("Given strip rule with single variation - when word is below min size - then returns empty")
    void givenStripRule_whenWordBelowMinSize_thenReturnsEmpty() {
        StemmableSuffix rule = StemmableSuffix.strip(6, "ing");

        Optional<String> result = rule.stem("ring");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Given strip rule with multiple variations - when any variation matches - then suffix is stripped")
    void givenStripRuleWithMultipleVariations_whenAnyMatches_thenSuffixStripped() {
        StemmableSuffix rule = StemmableSuffix.strip(5, "ize", "ise");

        assertThat(rule.stem("sanitize")).contains("sanit");
        assertThat(rule.stem("sanitise")).contains("sanit");
    }

    @Test
    @DisplayName("Given strip rule with multiple variations - when word below min size - then returns empty")
    void givenStripRuleWithMultipleVariations_whenWordBelowMinSize_thenReturnsEmpty() {
        StemmableSuffix rule = StemmableSuffix.strip(5, "ize", "ise");

        assertThat(rule.stem("size")).isEmpty();
    }

    @Test
    @DisplayName("Given replace rule without min size - when word ends with suffix - then suffix is replaced")
    void givenReplaceRule_whenWordMatches_thenSuffixReplaced() {
        StemmableSuffix rule = StemmableSuffix.replace("ies", "i");

        assertThat(rule.stem("queries")).contains("queri");
        assertThat(rule.stem("entities")).contains("entiti");
    }

    @Test
    @DisplayName("Given replace rule for sses - when word matches - then replaces with ss")
    void givenReplaceRuleForSses_whenWordMatches_thenReplacesWithSs() {
        StemmableSuffix rule = StemmableSuffix.replace("sses", "ss");

        assertThat(rule.stem("classes")).contains("class");
        assertThat(rule.stem("processes")).contains("process");
    }

    @Test
    @DisplayName("Given replace rule with min word size - when word meets min size - then replaces")
    void givenReplaceRuleWithMinWordSize_whenWordMeetsMinSize_thenReplaces() {
        StemmableSuffix rule = StemmableSuffix.replace(3, "i", "y");

        assertThat(rule.stem("try")).contains("tri");
        assertThat(rule.stem("by")).isEmpty();
    }

    @Test
    @DisplayName("Given preserve rule - when word ends with protected variation - then returns word unchanged")
    void givenPreserveRule_whenWordMatches_thenReturnsWordUnchanged() {
        StemmableSuffix rule = StemmableSuffix.preserve("ss", "us", "is");

        assertThat(rule.stem("process")).contains("process");
        assertThat(rule.stem("status")).contains("status");
        assertThat(rule.stem("analysis")).contains("analysis");
        assertThat(rule.preservesWord()).isTrue();
    }

    @Test
    @DisplayName("Given preserve rule - when word does not match - then returns empty")
    void givenPreserveRule_whenWordDoesNotMatch_thenReturnsEmpty() {
        StemmableSuffix rule = StemmableSuffix.preserve("ss", "us", "is");

        assertThat(rule.stem("rules")).isEmpty();
    }

    @Test
    @DisplayName("Given null or blank input - when stemming - then handles safely without exceptions")
    void givenNullOrBlankInput_whenStemming_thenReturnsEmpty() {
        StemmableSuffix rule = StemmableSuffix.strip(4, "s");

        assertThat(rule.stem(null)).isEmpty();
        assertThat(rule.stem("")).isEmpty();
    }

    @Test
    @DisplayName("Given word shorter than suffix - when stemming - then returns empty")
    void givenWordShorterThanSuffix_whenStemming_thenReturnsEmpty() {
        StemmableSuffix rule = StemmableSuffix.strip(-1, "ing");

        assertThat(rule.stem("in")).isEmpty();
    }

    @Test
    @DisplayName("Given mutable input list - when constructing rule - then creates defensive unmodifiable copy")
    void givenMutableInputList_whenConstructingRule_thenDefensivelyCopied() {
        List<String> mutableList = new ArrayList<>();
        mutableList.add("able");
        mutableList.add("ible");

        StemmableSuffix.SuffixRule rule = new StemmableSuffix.SuffixRule(mutableList, 6, "", false);
        mutableList.add("extra");

        List<String> variations = rule.variations();
        assertThat(variations).containsExactly("able", "ible");
        assertThatThrownBy(() -> variations.add("fail"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Given null variations list - when constructing SuffixRule - then throws NullPointerException")
    void givenNullVariationsList_whenConstructing_thenThrowsNpe() {
        assertThatThrownBy(() -> new StemmableSuffix.SuffixRule(null, -1, "", false))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Given null replacement - when constructing SuffixRule - then defaults to empty string")
    void givenNullReplacement_whenConstructing_thenDefaultsToEmptyString() {
        StemmableSuffix.SuffixRule rule = new StemmableSuffix.SuffixRule(List.of("ment"), 5, null, false);

        assertThat(rule.replacement()).isEmpty();
        assertThat(rule.stem("payment")).contains("pay");
    }
}
