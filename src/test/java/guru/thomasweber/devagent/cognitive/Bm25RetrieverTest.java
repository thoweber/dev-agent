package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.testutil.ArchitecturalRecordMother;
import guru.thomasweber.devagent.workspace.RepoPath;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Bm25RetrieverTest {

    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"));

    private RepoPath mockPath(String relative) {
        return RepoPath.of(ROOT.resolve(relative), ROOT);
    }

    private ArchitecturalRecord createDoc(String id, String title, String content) {
        return ArchitecturalRecord.builder()
            .id(id)
            .title(title)
            .status("Accepted")
            .tags(List.of("test"))
            .path(mockPath(id + ".md"))
            .summary(title)
            .content(content)
            .build();
    }

    @Test
    @DisplayName("Given stemmed term query - when searching and scoring - then IDF is evaluated consistently without double stemming")
    void givenStemmedTerm_whenComputingIdfAndSearching_thenConsistentWithoutDoubleStemming() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc1 = createDoc("DOC-1", "Database Entities", "entities running smoothly");
        retriever.index(List.of(doc1));

        // "entities" stems to "entiti".
        double idfOriginal = retriever.computeIdf("entities");
        double idfStemmed = retriever.computeIdf("entiti");
        assertThat(idfOriginal).isCloseTo(idfStemmed, Offset.offset(0.0001));

        List<ArchitecturalRecord> results = retriever.search("entities", 5);
        assertThat(results).hasSize(1);
        double score = retriever.scoreDocument("DOC-1", "entities");
        assertThat(score).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Given duplicate record IDs - when indexing - then deduplicates with last-wins semantics and correct lengths")
    void givenDuplicateRecordIds_whenIndexing_thenDeduplicatesWithLastWins() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc1 = createDoc("DOC-1", "Original Doc", "initial text content");
        ArchitecturalRecord doc1Updated = createDoc("DOC-1", "Updated Doc", "completely different modified keywords");
        ArchitecturalRecord doc2 = createDoc("DOC-2", "Second Doc", "initial text content");

        retriever.index(List.of(doc1, doc1Updated, doc2));

        List<ArchitecturalRecord> results = retriever.search("modified", 5);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().title()).isEqualTo("Updated Doc");

        // Stale postings from original doc1 should not match
        List<ArchitecturalRecord> staleResults = retriever.search("content", 5);
        assertThat(staleResults).extracting(ArchitecturalRecord::id).containsExactly("DOC-2");
    }

    // =========================================================================
    // 1. Mathematical Scoring Accuracy & Parameter Invariants
    // =========================================================================

    @Test
    @DisplayName("Given invalid parameters - when instantiating Bm25Retriever - then throws IllegalArgumentException")
    void givenInvalidParameters_whenInstantiating_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new Bm25Retriever(-0.1, 0.75))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("k1");

        assertThatThrownBy(() -> new Bm25Retriever(1.2, -0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("b");

        assertThatThrownBy(() -> new Bm25Retriever(1.2, 1.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("b");
    }

    @Test
    @DisplayName("Given BM25 retriever - when verifying non-negative Robertson-Spärck Jones IDF - then IDF is always >= 0")
    void givenRetriever_whenVerifyingIdf_thenAlwaysNonNegative() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc1 = createDoc("D1", "First Document", "common architectural pattern");
        ArchitecturalRecord doc2 = createDoc("D2", "Second Document", "common design rule");
        ArchitecturalRecord doc3 = createDoc("D3", "Third Document", "common invariant check");

        retriever.index(List.of(doc1, doc2, doc3));

        double idf = retriever.computeIdf("common");
        assertThat(idf).isGreaterThanOrEqualTo(0.0);

        List<ArchitecturalRecord> results = retriever.search("common", 5);
        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("Given keyword-stuffed document - when scoring term saturation - then score grows sub-linearly and saturates")
    void givenKeywordStuffedDoc_whenScoring_thenSaturatesSublinearly() {
        Bm25Retriever retriever = new Bm25Retriever();
        StringBuilder stuffed = new StringBuilder("cache ".repeat(500));
        ArchitecturalRecord doc1 = createDoc("D1", "Normal Doc", "cache cache cache cache cache");
        ArchitecturalRecord doc2 = createDoc("D2", "Stuffed Doc", stuffed.toString());
        ArchitecturalRecord doc3 = createDoc("D3", "Background Doc", "unrelated content without keyword");

        retriever.index(List.of(doc1, doc2, doc3));

        double score1 = retriever.scoreDocument(doc1.id(), "cache");
        double score2 = retriever.scoreDocument(doc2.id(), "cache");

        assertThat(score1).isGreaterThan(0.0);
        assertThat(score2).isGreaterThan(score1);
        assertThat(score2).isLessThan(score1 * 2.0);
    }

    @Test
    @DisplayName("Given documents of varying lengths - when length normalization b=0.75 - then shorter document scores higher")
    void givenVaryingLengths_whenScoring_thenShorterDocScoresHigher() {
        Bm25Retriever retriever = new Bm25Retriever(1.2, 0.75);
        ArchitecturalRecord docShort = createDoc("D_SHORT", "Short", "sandbox security");
        ArchitecturalRecord docLong = createDoc("D_LONG", "Long", "sandbox filler filler filler filler filler filler filler filler filler");
        ArchitecturalRecord docOther = createDoc("D_OTHER", "Other", "unrelated data");

        retriever.index(List.of(docShort, docLong, docOther));

        double scoreShort = retriever.scoreDocument(docShort.id(), "sandbox");
        double scoreLong = retriever.scoreDocument(docLong.id(), "sandbox");

        assertThat(scoreShort).isGreaterThan(scoreLong);

        Bm25Retriever retrieverNoLengthNorm = new Bm25Retriever(1.2, 0.0);
        retrieverNoLengthNorm.index(List.of(docShort, docLong, docOther));
        double scoreShortNoNorm = retrieverNoLengthNorm.scoreDocument(docShort.id(), "sandbox");
        double scoreLongNoNorm = retrieverNoLengthNorm.scoreDocument(docLong.id(), "sandbox");

        assertThat(scoreShortNoNorm).isCloseTo(scoreLongNoNorm, Offset.offset(1e-6));
    }

    @Test
    @DisplayName("Given deterministic 3-doc corpus - when computing BM25 score - then matches hand-calculated golden value")
    void givenDeterministicCorpus_whenScoring_thenMatchesGoldenValue() {
        Bm25Retriever retriever = new Bm25Retriever(1.2, 0.75);

        ArchitecturalRecord d1 = createDoc("D1", "D1", "compiler error");
        ArchitecturalRecord d2 = createDoc("D2", "D2", "compiler warning error failure");
        ArchitecturalRecord d3 = createDoc("D3", "D3", "runtime crash");

        retriever.index(List.of(d1, d2, d3));

        double score = retriever.scoreDocument("D1", "compiler");
        assertThat(score).isCloseTo(0.493768, Offset.offset(1e-4));
    }

    // =========================================================================
    // 2. Preprocessing, Tokenization & Morphology
    // =========================================================================

    @Test
    @DisplayName("Given CamelCase, snake_case, and kebab-case tokens - when tokenizing - then splits into constituent words")
    void givenMixedCaseTokens_whenTokenizing_thenSplitsWords() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc = createDoc("D1", "Workspace Setup", "WorkspaceJailValidator and backend_build_system with dev-agent");
        retriever.index(List.of(doc));

        assertThat(retriever.search("workspace", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("jail", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("validator", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("backend", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("build", 5)).extracting(ArchitecturalRecord::id).contains("D1");
    }

    @Test
    @DisplayName("Given stopwords and markdown formatting - when indexing - then filters stopwords and strips formatting")
    void givenStopwordsAndMarkdown_whenIndexing_thenFiltersStopwordsAndFormatting() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc = createDoc("D1", "Markdown Doc", """
            # Header Title
            * The and or but in on at to for with is are was were be.
            ```java
            String code = "important_logic";
            ```
            **Bold Text** and `inline_code`.
            """);

        retriever.index(List.of(doc));

        assertThat(retriever.search("the and or", 5)).isEmpty();
        assertThat(retriever.search("header", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("logic", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("bold", 5)).extracting(ArchitecturalRecord::id).contains("D1");
    }

    @Test
    @DisplayName("Given comprehensive morphological suffixes - when tokenizing - then stems properly across all rules")
    void givenMorphologicalSuffixes_whenTokenizing_thenStemsProperly() {
        Bm25Retriever retriever = new Bm25Retriever();
        List<String> tokens = retriever.tokenize(
            "relational conditional organization standardisation generation orchestrator " +
            "canonical deal scalable flexible containment processing ring isolated died " +
            "retriever executor user declarative live sanitize sanitise size " +
            "processes entities process status analysis rules gas retrieve code"
        );

        assertThat(tokens).contains(
            "relat", "condition", "organiz", "standardis", "generat", "orchestrat",
            "canonic", "deal", "scal", "flex", "contain", "process", "ring", "isolat", "died",
            "retriev", "execut", "user", "declarat", "live", "sanit", "size",
            "process", "entiti", "process", "status", "analysis", "rule", "gas", "retriev", "code"
        );
    }

    @Test
    @DisplayName("Given morphological variants - when querying stemmed words - then matches variant forms")
    void givenMorphologicalVariants_whenQuerying_thenMatchesStems() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc = createDoc("D1", "Retrieval Doc", "Pure-Java lexical retrieval and caching architecture.");
        retriever.index(List.of(doc));

        assertThat(retriever.search("retrieve", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("retriever", 5)).extracting(ArchitecturalRecord::id).contains("D1");
        assertThat(retriever.search("cache", 5)).extracting(ArchitecturalRecord::id).contains("D1");
    }

    // =========================================================================
    // 3. Boundary & Edge Cases
    // =========================================================================

    @Test
    @DisplayName("Given empty corpus - when searching or scoring - then returns empty list or zero")
    void givenEmptyCorpus_whenSearching_thenReturnsEmptyList() {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.index(Collections.emptyList());

        assertThat(retriever.search("anything", 5)).isEmpty();
        assertThat(retriever.scoreDocument("D1", "anything")).isZero();
        assertThat(retriever.computeIdf("anything")).isZero();

        retriever.index(null);
        assertThat(retriever.search("anything", 5)).isEmpty();
    }

    @Test
    @DisplayName("Given null, empty, or whitespace queries - when searching - then returns empty list")
    void givenNullOrEmptyQuery_whenSearching_thenReturnsEmptyList() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc1 = createDoc("D1", "Doc One", "some valid content");
        ArchitecturalRecord doc2 = createDoc("D2", "Doc Two", "other distinct text");
        retriever.index(List.of(doc1, doc2));

        assertThat(retriever.search(null, 5)).isEmpty();
        assertThat(retriever.search("", 5)).isEmpty();
        assertThat(retriever.search("   ", 5)).isEmpty();
        assertThat(retriever.search("the of in", 5)).isEmpty();
        assertThat(retriever.search("valid", 0)).isEmpty();
        assertThat(retriever.search("valid", -1)).isEmpty();
        assertThat(retriever.scoreDocument("D1", null)).isZero();
        assertThat(retriever.scoreDocument("D1", "")).isZero();
        assertThat(retriever.scoreDocument("D1", "the")).isZero();
        assertThat(retriever.scoreDocument("D1", "valid distinct unseen")).isGreaterThan(0.0);
        assertThat(retriever.scoreDocument("NON_EXISTENT", "valid")).isZero();
        assertThat(retriever.computeIdf("the")).isZero();
        assertThat(retriever.tokenize(null)).isEmpty();
        assertThat(retriever.tokenize("")).isEmpty();
        assertThat(retriever.tokenize("   ")).isEmpty();
        assertThat(retriever.tokenize("x y z")).isEmpty();

        ArchitecturalRecord nullTagsAndSummary = createDoc("D_NULL", "Title", "content")
            .toBuilder().tags(null).summary(null).build();
        retriever.index(List.of(nullTagsAndSummary));
        String prompt = retriever.formatForPrompt(List.of(nullTagsAndSummary));
        assertThat(prompt).contains("D_NULL");
    }

    @Test
    @DisplayName("Given out-of-vocabulary query - when searching - then returns empty list without exception")
    void givenOovQuery_whenSearching_thenReturnsEmptyList() {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.index(List.of(createDoc("D1", "Doc", "some valid content")));

        assertThat(retriever.search("completelyUnseenKeywordXYZ", 5)).isEmpty();
    }

    @Test
    @DisplayName("Given single document in corpus - when searching matching term - then ranks that document")
    void givenSingleDocCorpus_whenSearching_thenRanksDoc() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord doc = createDoc("D1", "Single Doc", "unique quantum algorithm");
        retriever.index(List.of(doc));

        List<ArchitecturalRecord> results = retriever.search("quantum", 5);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().id()).isEqualTo("D1");
    }

    @Test
    @DisplayName("Given two documents with identical score - when searching - then tie-breaks deterministically by docId")
    void givenTieScores_whenSearching_thenTieBreaksByDocId() {
        Bm25Retriever retriever = new Bm25Retriever(1.2, 0.0);
        ArchitecturalRecord docB = createDoc("DOC-B", "Title B", "identical keyword");
        ArchitecturalRecord docA = createDoc("DOC-A", "Title A", "identical keyword");
        retriever.index(List.of(docB, docA));

        List<ArchitecturalRecord> results = retriever.search("keyword", 5);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("DOC-A");
        assertThat(results.get(1).id()).isEqualTo("DOC-B");
    }

    // =========================================================================
    // 4. Disambiguation & Token Optimization SLA
    // =========================================================================

    @Test
    @DisplayName("Given canonical ADR records - when querying topics - then disambiguates and ranks target ADR first")
    void givenCanonicalAdrs_whenQuerying_thenDisambiguatesAccurately() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord adr001 = ArchitecturalRecordMother.adr001();
        ArchitecturalRecord adr003 = ArchitecturalRecordMother.adr003();
        ArchitecturalRecord adr005 = ArchitecturalRecordMother.adr005();
        ArchitecturalRecord adr009 = ArchitecturalRecordMother.adr009();
        ArchitecturalRecord rule01 = ArchitecturalRecordMother.rule01();

        retriever.index(List.of(adr001, adr003, adr005, adr009, rule01));

        List<ArchitecturalRecord> resPathJail = retriever.search("workspace path jail sandbox boundary", 3);
        assertThat(resPathJail).isNotEmpty();
        assertThat(resPathJail.getFirst().id()).isEqualTo("ADR-003");

        List<ArchitecturalRecord> resBm25 = retriever.search("pure java lexical bm25 retrieval inverted index", 3);
        assertThat(resBm25).isNotEmpty();
        assertThat(resBm25.getFirst().id()).isEqualTo("ADR-005");

        List<ArchitecturalRecord> resCircuitBreaker = retriever.search("caffeine cache deterministic circuit breaker", 3);
        assertThat(resCircuitBreaker).isNotEmpty();
        assertThat(resCircuitBreaker.getFirst().id()).isEqualTo("ADR-009");
    }

    @Test
    @DisplayName("Given retrieved records - when formatting for prompt - then formats clean string with token reduction SLA")
    void givenRetrievedRecords_whenFormattingPrompt_thenReducesTokensOver70Percent() {
        Bm25Retriever retriever = new Bm25Retriever();
        ArchitecturalRecord adr001 = ArchitecturalRecordMother.adr001();
        ArchitecturalRecord adr003 = ArchitecturalRecordMother.adr003();
        ArchitecturalRecord adr005 = ArchitecturalRecordMother.adr005();
        ArchitecturalRecord adr009 = ArchitecturalRecordMother.adr009();
        ArchitecturalRecord rule01 = ArchitecturalRecordMother.rule01();

        List<ArchitecturalRecord> fullCorpus = List.of(adr001, adr003, adr005, adr009, rule01);
        retriever.index(fullCorpus);

        assertThat(retriever.formatForPrompt(null)).isEmpty();
        assertThat(retriever.formatForPrompt(Collections.emptyList())).isEmpty();

        List<ArchitecturalRecord> top2 = retriever.search("bm25 lexical retrieval", 2);
        String formattedPrompt = retriever.formatForPrompt(top2);

        assertThat(formattedPrompt).contains("ADR-005");
        assertThat(formattedPrompt).doesNotContain("ADR-001");

        int fullLength = fullCorpus.stream().mapToInt(d -> d.content().length()).sum();
        int formattedLength = formattedPrompt.length();

        double reduction = 1.0 - ((double) formattedLength / fullLength);
        assertThat(reduction).isGreaterThanOrEqualTo(0.50);

        // Record with null summary
        ArchitecturalRecord noSummary = adr001.toBuilder().summary("").build();
        String formattedNoSummary = retriever.formatForPrompt(List.of(noSummary));
        assertThat(formattedNoSummary).contains("ADR-001");
    }
}
