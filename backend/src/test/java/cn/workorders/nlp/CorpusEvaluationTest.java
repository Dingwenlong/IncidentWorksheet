package cn.workorders.nlp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Self-constructed equipment tickets; facts are verbatim annotated spans in the corresponding
 * field.
 */
class CorpusEvaluationTest {
  record FieldCase(String raw, List<String> facts) {}

  record Example(String id, FieldCase fault, FieldCase action) {}

  static List<Example> examples() throws Exception {
    var rows = SnapshotBuilder.lines("evaluation.tsv");
    var examples = new ArrayList<Example>();
    Set<String> identifiers = new HashSet<>();
    Set<String> development = new HashSet<>();
    for (String s : SnapshotBuilder.lines("development.tsv").subList(1, 61)) {
      development.add(s.split("\t", -1)[1]);
    }
    for (String line : rows.subList(1, rows.size())) {
      var cells = line.split("\t", -1);
      assertEquals(5, cells.length);
      assertTrue(identifiers.add(cells[0]));
      assertFalse(development.contains(cells[1]));
      examples.add(new Example(cells[0], field(cells[1], cells[3]), field(cells[2], cells[4])));
    }
    assertEquals(40, examples.size());
    return examples;
  }

  static FieldCase field(String raw, String labels) {
    var facts = labels.isEmpty() ? List.<String>of() : List.of(labels.split(",", -1));
    assertEquals(raw.isEmpty(), facts.isEmpty());
    for (var fact : facts) {
      assertFalse(fact.isBlank());
      assertTrue(raw.contains(fact), () -> "Expected exact annotated span: " + fact);
    }
    return new FieldCase(raw, facts);
  }

  static class Totals {
    int fields, facts, covered, located;

    void add(FieldCase source, String sentence, boolean matches) {
      fields++;
      facts += source.facts().size();
      if (matches) located++;
      if (sentence != null) for (var fact : source.facts()) if (sentence.contains(fact)) covered++;
    }

    Map<String, Integer> values() {
      return Map.of(
          "fields", fields, "facts", facts, "covered", covered, "verifiedNonemptySpans", located);
    }
  }

  @Test
  void compareFrozenStrategies() throws Exception {
    var resource = NlpResources.load();
    var engine = new SummaryEngine(resource);
    var cases = examples();
    var report = new LinkedHashMap<String, Object>();
    report.put("source", "自构维修记录；要点为预设的原文片段，按完整字面包含计数");
    report.put("development", 60);
    report.put("evaluation", 40);
    report.put("dictionaryVersion", NlpResources.DICTIONARY);
    report.put("idfVersion", NlpResources.IDF);
    report.put(
        "runtime",
        System.getProperty("java.runtime.version")
            + " / "
            + System.getProperty("os.name")
            + " "
            + System.getProperty("os.arch"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("timing", "单线程，每种算法预热一轮、计时五轮；包括分词、选句，不含数据库与网络");
    var details = new LinkedHashMap<String, Map<String, Map<String, Object>>>();
    for (String strategy : List.of("TFIDF", "TEXTRANK")) {
      var totals = new TreeMap<String, Totals>();
      var warm = new HashMap<String, Map<String, Object>>();
      for (Example row : cases) {
        var result = engine.generateOne(strategy, row.fault().raw(), row.action().raw(), 1);
        warm.put(row.id(), result);
        engine.validate(result, strategy, 1, row.fault().raw(), row.action().raw());
        for (var key : List.of("fault", "action")) {
          FieldCase src = key.equals("fault") ? row.fault() : row.action();
          var selected = (Map<?, ?>) result.get(key);
          String sentence = (String) selected.get("sentence");
          int candidates = resource.tokenizer().prepare(src.raw()).sentences().size();
          String group = candidates == 0 ? "empty" : candidates == 1 ? "single" : "multiple";
          boolean located = false;
          if (sentence == null) assertTrue(src.raw().isEmpty());
          else {
            assertEquals(
                sentence,
                src.raw().substring((int) selected.get("start"), (int) selected.get("end")));
            located = true;
          }
          totals.computeIfAbsent(key + ".all", unused -> new Totals()).add(src, sentence, located);
          totals
              .computeIfAbsent(key + "." + group, unused -> new Totals())
              .add(src, sentence, located);
        }
      }
      List<Long> nanos = new ArrayList<>(200);
      for (int round = 0; round < 5; round++)
        for (Example row : cases) {
          long start = System.nanoTime();
          var result = engine.generateOne(strategy, row.fault().raw(), row.action().raw(), 1);
          nanos.add(System.nanoTime() - start);
          assertEquals(warm.get(row.id()), result);
        }
      Collections.sort(nanos);
      var counts = new TreeMap<String, Object>();
      totals.forEach((key, val) -> counts.put(key, val.values()));
      var summary = new LinkedHashMap<String, Object>();
      summary.put("counts", counts);
      summary.put("measurements", nanos.size());
      summary.put("medianMillis", (nanos.get(99) + nanos.get(100)) / 2_000_000d);
      summary.put("p95Millis", nanos.get(189) / 1_000_000d);
      report.put(strategy, summary);
      details.put(strategy, warm);
      System.out.println(strategy + " " + NlpResources.JSON.writeValueAsString(summary));
    }
    var differences = new ArrayList<Map<String, Object>>();
    for (Example example : cases) {
      for (String key : List.of("fault", "action")) {
        FieldCase field = key.equals("fault") ? example.fault() : example.action();
        var tf = (Map<?, ?>) details.get("TFIDF").get(example.id()).get(key);
        var tr = (Map<?, ?>) details.get("TEXTRANK").get(example.id()).get(key);
        if (Objects.equals(tf.get("start"), tr.get("start"))) continue;
        String first = (String) tf.get("sentence");
        String second = (String) tr.get("sentence");
        differences.add(
            Map.of(
                "id",
                example.id(),
                "field",
                key,
                "original",
                field.raw(),
                "TFIDF",
                first,
                "TEXTRANK",
                second,
                "missedByTfidf",
                field.facts().stream().filter(fact -> !first.contains(fact)).toList(),
                "missedByTextRank",
                field.facts().stream().filter(fact -> !second.contains(fact)).toList()));
      }
    }
    report.put("differentSelections", differences);
    report.put("cases", details);
    var dest = Path.of("target", "nlp-evaluation.json");
    Files.createDirectories(dest.getParent());
    NlpResources.JSON.writerWithDefaultPrettyPrinter().writeValue(dest.toFile(), report);
  }
}
