package cn.workorders.nlp;

import static org.junit.jupiter.api.Assertions.*;

import cn.workorders.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryEngineTest {
  private final ChineseText tokenizer = new ChineseText(List.of("未恢复"), List.of("的", "了"));

  @Test
  void frozenSnapshotIsExactlyRebuildable() throws Exception {
    try (var input = getClass().getResourceAsStream("/nlp/idf.json")) {
      assertNotNull(input);
      assertEquals(
          SnapshotBuilder.snapshot(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertEquals(60, NlpResources.load().idf().N());
  }

  @Test
  void documentFrequencyCountsAnOrderOnlyOnce() {
    var df =
        SnapshotBuilder.frequencies(
            tokenizer, List.of("1\tP-02 E17\tR1 E17", "2\tP-02 R1\t", "3\tR1\t"));
    assertEquals(Map.of("e17", 1, "p-02", 2, "r1", 3), df);
    var idf = new TermWeights.Idf("fixture", "dict-v1", 3, df);
    var field = tokenizer.prepare("P-02 E17。R1 R1。");
    var weights = TermWeights.tfidf(field, idf);
    assertEquals(0.423287, weights.get("e17"), 1e-6);
    assertEquals(0.321921, weights.get("p-02"), 1e-6);
    assertEquals(0.5, weights.get("r1"), 1e-12);
    var result = TermWeights.select(field, weights);
    assertEquals("P-02 E17。", result.get("sentence"));
    assertEquals(List.of("R1", "E17", "P-02"), result.get("keywords"));
    assertEquals(9, result.get("end"));
    assertEquals(1 + Math.log(4), TermWeights.tfidf(tokenizer.prepare("X9"), idf).get("x9"), 1e-12);
    assertThrows(
        IllegalArgumentException.class, () -> new TermWeights.Idf("bad", "dict-v1", 2, df));
  }

  @Test
  void graphRetainsIsolatesAndUsesSameSentenceWindows() {
    var field = tokenizer.prepare("A1 B2。B2 C3。D4。");
    var ranks = TermWeights.textRank(field);
    assertEquals(0.244881, ranks.get("a1"), 1e-6);
    assertEquals(0.462619, ranks.get("b2"), 1e-6);
    assertEquals(ranks.get("a1"), ranks.get("c3"), 1e-12);
    assertEquals(0.047619, ranks.get("d4"), 1e-6);
    var result = TermWeights.select(field, ranks);
    assertEquals("A1 B2。", result.get("sentence"));
    assertEquals(List.of("B2", "A1", "C3", "D4"), result.get("keywords"));
    assertEquals(
        List.of("A1", "B2", "C3", "D4", "E5"),
        TermWeights.select(
                tokenizer.prepare("A1。B2。C3。D4。E5。F6。"),
                Map.of("a1", 1d, "b2", 1d, "c3", 1d, "d4", 1d, "e5", 1d, "f6", 1d))
            .get("keywords"));
  }

  @Test
  void utf16SpansReferToOriginalCaseAndWhitespace() {
    var field = tokenizer.prepare("🔧 E17！\r\n未恢复。");
    assertEquals(0, field.sentences().get(0).start());
    assertEquals(7, field.sentences().get(0).end());
    assertEquals(9, field.sentences().get(1).start());
    assertEquals(13, field.sentences().get(1).end());
    assertEquals(List.of("未恢复"), field.sentences().get(1).tokens());
    assertEquals("P-02", tokenizer.prepare("P-02 p-02 E17").display().get("p-02"));
    var f = tokenizer.prepare("停机？！停机？！");
    assertEquals(4, f.sentences().get(0).end());
  }

  @Test
  void emptyFallbackAndGeneratedRowsValidate() throws Exception {
    var engine = new SummaryEngine(NlpResources.load());
    for (String field : List.of("  ", "！？；", "的。了。", "P-02。", "E17。E17。")) {
      var pair = engine.generate(field, "", 3);
      for (var entry : pair.entrySet()) {
        var row = entry.getValue();
        engine.validate(row, entry.getKey(), 3, field, "");
        engine.validate(row, entry.getKey(), 3, null, null);
        var fault = (Map<?, ?>) row.get("fault");
        var method =
            switch (field) {
              case "  " -> "EMPTY";
              case "！？；", "的。了。" -> "FALLBACK";
              case "P-02。" -> "SINGLE";
              default -> "RANKED";
            };
        assertEquals(method, fault.get("selection"));
        assertEquals("EMPTY", ((Map<?, ?>) row.get("action")).get("selection"));
        if (!field.isBlank()) assertEquals(0, fault.get("start"));
      }
    }
    var result = engine.generate("E17。", "", 1).get("TFIDF");
    assertEquals(
        500,
        assertThrows(ApiException.class, () -> engine.validate(result, "TFIDF", 2, "E17。", ""))
            .status());
    assertEquals(
        500,
        assertThrows(ApiException.class, () -> engine.validate(result, "TFIDF", 1, "E18。", ""))
            .status());
    assertEquals(
        503,
        assertThrows(ApiException.class, () -> new SummaryEngine(null).generate("E17。", "", 1))
            .status());
  }
}
