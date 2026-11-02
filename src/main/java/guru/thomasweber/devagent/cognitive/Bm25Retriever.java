package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Pure-Java lexical inverted index retrieval engine implementing Okapi BM25 ranking.
 * Backed by an immutable snapshot architecture for lock-free concurrent reads.
 * Provides zero-JNI native safety for GraalVM Mandrel Ahead-of-Time (AOT) compilation.
 */
@ApplicationScoped
public class Bm25Retriever {

    public static final double DEFAULT_K1 = 1.2;
    public static final double DEFAULT_B = 0.75;

    private static final Pattern CAMEL_CASE_1 = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern CAMEL_CASE_2 = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]+");

    private static final Set<String> STOPWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are", "as", "at",
        "be", "because", "been", "before", "being", "below", "between", "both", "but", "by", "could", "did",
        "do", "does", "doing", "down", "during", "each", "few", "for", "from", "further", "had", "has", "have",
        "having", "he", "her", "here", "hers", "herself", "him", "himself", "his", "how", "i", "if", "in",
        "into", "is", "it", "its", "itself", "just", "me", "more", "most", "my", "myself", "no", "nor", "not",
        "now", "of", "off", "on", "once", "only", "or", "other", "our", "ours", "ourselves", "out", "over",
        "own", "same", "she", "should", "so", "some", "such", "than", "that", "the", "their", "theirs", "them",
        "themselves", "then", "there", "these", "they", "this", "those", "through", "to", "too", "under",
        "until", "up", "very", "was", "we", "were", "what", "when", "where", "which", "while", "who", "whom",
        "why", "with", "would", "you", "your", "yours", "yourself", "yourselves"
    )));

    private static final List<StemmableSuffix> PLURAL_RULES = List.of(
        StemmableSuffix.replace("sses", "ss"),
        StemmableSuffix.replace("ies", "i"),
        StemmableSuffix.preserve("ss", "us", "is"),
        StemmableSuffix.strip(4, "s")
    );

    private static final List<StemmableSuffix> NOUN_RULES = List.of(
        StemmableSuffix.replace("ational", "at"),
        StemmableSuffix.replace("tional", "tion"),
        StemmableSuffix.replace("ization", "iz"),
        StemmableSuffix.replace("isation", "is"),
        StemmableSuffix.replace("ation", "at"),
        StemmableSuffix.replace("ator", "at"),
        StemmableSuffix.strip(6, "ment")
    );

    private static final List<StemmableSuffix> ADJECTIVE_RULES = List.of(
        StemmableSuffix.strip(5, "al"),
        StemmableSuffix.strip(6, "able", "ible"),
        StemmableSuffix.strip(5, "ive")
    );

    private static final List<StemmableSuffix> VERB_RULES = List.of(
        StemmableSuffix.strip(6, "ing"),
        StemmableSuffix.strip(5, "ed"),
        StemmableSuffix.strip(5, "er", "or"),
        StemmableSuffix.strip(5, "ize", "ise")
    );

    private static final List<StemmableSuffix> TRAILING_VOWEL_RULES = List.of(
        StemmableSuffix.strip(5, "e"),
        StemmableSuffix.replace(3, "i", "y")
    );

    private record IndexSnapshot(
        Map<String, ArchitecturalRecord> documents,
        Map<String, Integer> documentLengths,
        Map<String, Map<String, Integer>> invertedIndex,
        double avgDocLength,
        int totalDocs
    ) {
        static IndexSnapshot empty() {
            return new IndexSnapshot(Map.of(), Map.of(), Map.of(), 0.0, 0);
        }
    }

    private final double k1;
    private final double b;
    private final AtomicReference<IndexSnapshot> snapshot = new AtomicReference<>(IndexSnapshot.empty());

    public Bm25Retriever() {
        this(DEFAULT_K1, DEFAULT_B);
    }

    public Bm25Retriever(double k1, double b) {
        if (k1 < 0.0) {
            throw new IllegalArgumentException("k1 must be non-negative");
        }
        if (b < 0.0 || b > 1.0) {
            throw new IllegalArgumentException("b must be between 0.0 and 1.0");
        }
        this.k1 = k1;
        this.b = b;
    }

    public void index(List<ArchitecturalRecord> records) {
        if (records == null || records.isEmpty()) {
            snapshot.set(IndexSnapshot.empty());
            return;
        }

        Map<String, ArchitecturalRecord> uniqueRecords = new LinkedHashMap<>();
        for (ArchitecturalRecord rec : records) {
            if (rec != null && rec.id() != null) {
                uniqueRecords.put(rec.id(), rec);
            }
        }

        if (uniqueRecords.isEmpty()) {
            snapshot.set(IndexSnapshot.empty());
            return;
        }

        Map<String, ArchitecturalRecord> newDocs = new LinkedHashMap<>();
        Map<String, Integer> newLengths = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> newInvertedIndex = new LinkedHashMap<>();

        long totalTokens = 0;
        for (ArchitecturalRecord rec : uniqueRecords.values()) {
            newDocs.put(rec.id(), rec);
            totalTokens += indexSingleRecord(rec, newLengths, newInvertedIndex);
        }

        int totalDocs = newDocs.size();
        double avgDocLength = totalDocs > 0 ? (double) totalTokens / totalDocs : 0.0;

        Map<String, Map<String, Integer>> frozenIndex = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : newInvertedIndex.entrySet()) {
            frozenIndex.put(e.getKey(), Collections.unmodifiableMap(e.getValue()));
        }

        snapshot.set(new IndexSnapshot(
            Collections.unmodifiableMap(newDocs),
            Collections.unmodifiableMap(newLengths),
            Collections.unmodifiableMap(frozenIndex),
            avgDocLength,
            totalDocs
        ));
    }

    public double computeIdf(String term) {
        IndexSnapshot current = snapshot.get();
        if (current.totalDocs() == 0 || term == null || term.isBlank()) {
            return 0.0;
        }
        List<String> tokens = tokenize(term);
        if (tokens.isEmpty()) {
            return 0.0;
        }
        return computeIdfForStem(current, tokens.getFirst());
    }

    private double computeIdfForStem(IndexSnapshot current, String stem) {
        Map<String, Integer> postings = current.invertedIndex().get(stem);
        int docFreq = (postings != null) ? postings.size() : 0;

        // Robertson-Spärck Jones / Lucene non-negative IDF: ln(1 + (N - n(t) + 0.5) / (n(t) + 0.5))
        double numerator = current.totalDocs() - docFreq + 0.5;
        double denominator = docFreq + 0.5;
        return Math.log(1.0 + (numerator / denominator));
    }

    public double scoreDocument(String docId, String query) {
        IndexSnapshot current = snapshot.get();
        if (current.totalDocs() == 0 || query == null || !current.documents().containsKey(docId)) {
            return 0.0;
        }
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0;
        }

        int docLength = current.documentLengths().getOrDefault(docId, 0);
        double lenNorm = (current.avgDocLength() > 0.0) ? (1.0 - b + b * (docLength / current.avgDocLength())) : 1.0;

        double totalScore = 0.0;
        for (String token : queryTokens) {
            Map<String, Integer> postings = current.invertedIndex().get(token);
            if (postings != null && postings.containsKey(docId)) {
                int freq = postings.get(docId);
                double idf = computeIdfForStem(current, token);
                double tfPart = (freq * (k1 + 1.0)) / (freq + k1 * lenNorm);
                totalScore += idf * tfPart;
            }
        }
        return totalScore;
    }

    public List<ArchitecturalRecord> search(String query, int topK) {
        IndexSnapshot current = snapshot.get();
        if (query == null || query.isBlank() || current.totalDocs() == 0 || topK <= 0) {
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> scores = new LinkedHashMap<>();

        for (String token : queryTokens) {
            Map<String, Integer> postings = current.invertedIndex().get(token);
            if (postings == null) {
                continue;
            }

            double idf = computeIdfForStem(current, token);
            for (Map.Entry<String, Integer> entry : postings.entrySet()) {
                String docId = entry.getKey();
                int freq = entry.getValue();
                int docLength = current.documentLengths().getOrDefault(docId, 0);
                double lenNorm = (current.avgDocLength() > 0.0) ? (1.0 - b + b * (docLength / current.avgDocLength())) : 1.0;
                double tfPart = (freq * (k1 + 1.0)) / (freq + k1 * lenNorm);
                scores.merge(docId, idf * tfPart, Double::sum);
            }
        }

        if (scores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((e1, e2) -> {
            int cmp = Double.compare(e2.getValue(), e1.getValue());
            if (cmp != 0) {
                return cmp;
            }
            return e1.getKey().compareTo(e2.getKey());
        });

        List<ArchitecturalRecord> results = new ArrayList<>();
        int count = Math.min(topK, sorted.size());
        for (int i = 0; i < count; i++) {
            results.add(current.documents().get(sorted.get(i).getKey()));
        }

        return Collections.unmodifiableList(results);
    }

    public String formatForPrompt(List<ArchitecturalRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### RETRIEVED ARCHITECTURAL DECISIONS & RULES:%n"));
        for (ArchitecturalRecord rec : records) {
            sb.append(String.format("- [%s] %s (Status: %s)%n", rec.id(), rec.title(), rec.status()));
            if (rec.summary() != null && !rec.summary().isBlank()) {
                sb.append(String.format("  Summary: %s%n", rec.summary().strip()));
            }
        }
        return sb.toString();
    }

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // 1. Split CamelCase (e.g. WorkspaceJailValidator -> Workspace Jail Validator)
        String s = CAMEL_CASE_1.matcher(text).replaceAll("$1 $2");
        s = CAMEL_CASE_2.matcher(s).replaceAll("$1 $2");

        // 2. Replace kebab-case and snake_case with spaces
        s = s.replace('-', ' ').replace('_', ' ');

        // 3. Split by non-alphanumeric characters
        String[] rawTokens = NON_ALPHANUMERIC.split(s);

        List<String> tokens = new ArrayList<>();
        for (String raw : rawTokens) {
            String lower = raw.trim().toLowerCase(Locale.ROOT);
            if (lower.length() < 2 || STOPWORDS.contains(lower)) {
                continue;
            }
            tokens.add(stem(lower));
        }

        return tokens;
    }

    private String stem(String word) {
        if (word.length() <= 2) {
            return word;
        }
        String w = applyFirst(word, PLURAL_RULES);
        w = stemDerivational(w);
        return applyFirst(w, TRAILING_VOWEL_RULES);
    }

    private int indexSingleRecord(ArchitecturalRecord rec, Map<String, Integer> lengths, Map<String, Map<String, Integer>> invertedIndex) {
        String fullText = String.join(" ",
            Objects.toString(rec.title(), ""),
            Objects.toString(rec.summary(), ""),
            Objects.toString(rec.content(), ""),
            String.join(" ", rec.tags() != null ? rec.tags() : Collections.emptyList())
        );

        List<String> tokens = tokenize(fullText);
        int docLength = tokens.size();
        lengths.put(rec.id(), docLength);

        Map<String, Integer> freqs = new LinkedHashMap<>();
        for (String token : tokens) {
            freqs.merge(token, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : freqs.entrySet()) {
            invertedIndex.computeIfAbsent(entry.getKey(), _ -> new LinkedHashMap<>())
                .put(rec.id(), entry.getValue());
        }
        return docLength;
    }

    private static String stemDerivational(String w) {
        String stemmed = applyFirst(w, NOUN_RULES);
        if (!stemmed.equals(w)) {
            return stemmed;
        }
        stemmed = applyFirst(w, ADJECTIVE_RULES);
        if (!stemmed.equals(w)) {
            return stemmed;
        }
        return applyFirst(w, VERB_RULES);
    }

    private static String applyFirst(String word, List<StemmableSuffix> rules) {
        for (StemmableSuffix rule : rules) {
            Optional<String> result = rule.stem(word);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return word;
    }
}
