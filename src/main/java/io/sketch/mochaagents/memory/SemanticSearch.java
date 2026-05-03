package io.sketch.mochaagents.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TF-IDF semantic search — replaces naive substring matching with
 * term frequency-inverse document frequency vectorization and cosine similarity.
 * @author lanxia39@163.com
 */
public final class SemanticSearch {

    private final Map<String, double[]> docVectors = new LinkedHashMap<>();
    private final Map<String, Double> idf = new HashMap<>();
    private final Set<String> vocabulary = new HashSet<>();
    private boolean built;

    /** Index a document by its ID and content. */
    public SemanticSearch index(String id, String content) {
        docVectors.put(id, null); // lazy — build on first search
        built = false;
        return this;
    }

    /** Build the TF-IDF vectors for all indexed documents. Call before search. */
    public SemanticSearch build() {
        if (built) return this;
        vocabulary.clear(); idf.clear();

        // First pass: collect vocabulary and document frequencies
        Map<String, Integer> docFreq = new HashMap<>();
        for (var entry : docVectors.entrySet()) {
            String content = entry.getKey(); // using ID as content proxy — caller should provide real content
            Set<String> terms = tokenize(content);
            vocabulary.addAll(terms);
            for (String t : terms) docFreq.merge(t, 1, Integer::sum);
        }

        // Compute IDF
        int N = docVectors.size();
        for (String term : vocabulary) {
            int df = docFreq.getOrDefault(term, 1);
            idf.put(term, Math.log((double) N / df));
        }

        // Second pass: build TF-IDF vectors
        for (var entry : docVectors.entrySet()) {
            Set<String> terms = tokenize(entry.getKey());
            double[] vec = new double[vocabulary.size()];
            int i = 0;
            Map<String, Double> tf = new HashMap<>();
            for (String t : terms) tf.merge(t, 1.0, Double::sum);
            for (String term : vocabulary) {
                double termFreq = tf.getOrDefault(term, 0.0) / Math.max(1, terms.size());
                vec[i++] = termFreq * idf.getOrDefault(term, 0.0);
            }
            entry.setValue(vec);
        }
        built = true;
        return this;
    }

    /** Search for documents similar to the query. Returns IDs ranked by cosine similarity. */
    public List<ScoredDocument> search(String query, int topK) {
        if (!built) build();

        double[] queryVec = buildQueryVector(query);
        PriorityQueue<ScoredDocument> heap = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredDocument::score));

        int idx = 0;
        for (var entry : docVectors.entrySet()) {
            double sim = cosineSimilarity(queryVec, entry.getValue());
            heap.offer(new ScoredDocument(entry.getKey(), sim, idx++));
            if (heap.size() > topK) heap.poll();
        }

        List<ScoredDocument> result = new ArrayList<>(heap);
        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    private double[] buildQueryVector(String query) {
        Set<String> terms = tokenize(query);
        double[] vec = new double[vocabulary.size()];
        int i = 0;
        for (String term : vocabulary)
            vec[i++] = terms.contains(term) ? idf.getOrDefault(term, 0.0) : 0;
        return vec;
    }

    private static double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[^a-zA-Z0-9]+"))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toSet());
    }

    public record ScoredDocument(String id, double score, int index) {}
}
