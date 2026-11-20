package cn.workorders.nlp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

final class TermWeights {
  private TermWeights() {}

  record Idf(String version, String dictionaryVersion, int N, Map<String, Integer> df) {
    Idf {
      if (version == null
          || version.isBlank()
          || dictionaryVersion == null
          || dictionaryVersion.isBlank()
          || N < 1) throw new IllegalArgumentException("Invalid snapshot metadata");
      df = Map.copyOf(df);
      for (var term : df.entrySet()) {
        if (term.getKey().isEmpty() || term.getValue() <= 0 || term.getValue() > N)
          throw new IllegalArgumentException("Invalid document frequency");
      }
    }
  }

  static Map<String, Double> tfidf(ChineseText.Field field, Idf snapshot) {
    var counts = new LinkedHashMap<String, Integer>();
    int length = 0;
    for (var sentence : field.sentences())
      for (String term : sentence.tokens()) {
        counts.merge(term, 1, Integer::sum);
        length++;
      }
    var weights = new LinkedHashMap<String, Double>();
    for (var entry : counts.entrySet()) {
      double idf =
          Math.log((snapshot.N() + 1.0) / (snapshot.df().getOrDefault(entry.getKey(), 0) + 1.0))
              + 1.0;
      weights.put(entry.getKey(), entry.getValue() / (double) length * idf);
    }
    return weights;
  }

  static Map<String, Double> textRank(ChineseText.Field field) {
    List<String> terms = List.copyOf(field.display().keySet());
    int size = terms.size();
    var edges = new ArrayList<Map<Integer, Integer>>(size);
    var index = new HashMap<String, Integer>();
    int[] degree = new int[size];
    for (int i = 0; i < size; i++) {
      index.put(terms.get(i), i);
      edges.add(new TreeMap<>());
    }
    for (var sentence : field.sentences()) {
      var words = sentence.tokens();
      for (int i = 0; i < words.size(); i++) {
        int a = index.get(words.get(i));
        for (int j = i + 1; j < words.size() && j <= i + 4; j++) {
          int b = index.get(words.get(j));
          if (a == b) continue;
          edges.get(a).merge(b, 1, Integer::sum);
          edges.get(b).merge(a, 1, Integer::sum);
          degree[a]++;
          degree[b]++;
        }
      }
    }
    var weights = new LinkedHashMap<String, Double>();
    if (size == 0) return weights;
    double[] rank = new double[size];
    Arrays.fill(rank, 1.0 / size);
    for (int k = 0; k < 30; k++) {
      double dangling = 0;
      for (int i = 0; i < size; i++) if (degree[i] == 0) dangling += rank[i];
      double[] next = new double[size];
      for (int i = 0; i < size; i++) {
        double transmitted = 0;
        for (var edge : edges.get(i).entrySet()) {
          int u = edge.getKey();
          transmitted += rank[u] * edge.getValue() / degree[u];
        }
        next[i] = (1 - 0.85) / size + 0.85 * (dangling / size + transmitted);
      }
      rank = next;
    }
    for (int i = 0; i < size; i++) weights.put(terms.get(i), rank[i]);
    return weights;
  }

  static Map<String, Object> select(ChineseText.Field field, Map<String, Double> weights) {
    if (!field.display().keySet().equals(weights.keySet())
        || weights.values().stream().anyMatch(v -> v == null || !Double.isFinite(v) || v < 0))
      throw new IllegalArgumentException("Invalid term weights");
    var sentences = field.sentences();
    ChineseText.Sentence chosen = null;
    String method;
    if (sentences.isEmpty()) method = "EMPTY";
    else if (weights.isEmpty()) {
      method = "FALLBACK";
      chosen = sentences.get(0);
    } else if (sentences.size() == 1) {
      method = "SINGLE";
      chosen = sentences.get(0);
    } else {
      method = "RANKED";
      BigDecimal best = null;
      for (var sentence : sentences) {
        if (sentence.tokens().isEmpty()) continue;
        double sum = 0;
        for (String term : new LinkedHashSet<>(sentence.tokens())) sum += weights.get(term);
        var score = rounded(sum / Math.sqrt(sentence.tokens().size()));
        if (best == null || score.compareTo(best) > 0) {
          chosen = sentence;
          best = score;
        }
      }
    }
    Map<String, BigDecimal> roundedWeights = new HashMap<>();
    weights.forEach((term, weight) -> roundedWeights.put(term, rounded(weight)));
    // Java's stable sort retains the original first-occurrence order for ties.
    List<String> keywords =
        field.display().keySet().stream()
            .sorted(Comparator.comparing(roundedWeights::get, Comparator.reverseOrder()))
            .limit(5)
            .map(field.display()::get)
            .toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("sentence", chosen == null ? null : chosen.text());
    result.put("start", chosen == null ? null : chosen.start());
    result.put("end", chosen == null ? null : chosen.end());
    result.put("keywords", keywords);
    result.put("selection", method);
    return result;
  }

  private static BigDecimal rounded(double value) {
    return BigDecimal.valueOf(value).setScale(12, RoundingMode.HALF_EVEN);
  }
}
