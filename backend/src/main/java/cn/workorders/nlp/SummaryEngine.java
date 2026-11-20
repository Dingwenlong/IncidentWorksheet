package cn.workorders.nlp;

import cn.workorders.common.ApiException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SummaryEngine {
  private static final Logger LOG = LoggerFactory.getLogger(SummaryEngine.class);
  private final NlpResources resources;

  public SummaryEngine() {
    NlpResources loaded = null;
    try {
      loaded = NlpResources.load();
    } catch (Exception error) {
      LOG.error("摘要资源校验失败，已保存的摘要仍可读取", error);
    }
    resources = loaded;
  }

  SummaryEngine(NlpResources resources) {
    this.resources = resources;
  }

  public Map<String, Map<String, Object>> generate(
      String faultText, String actionText, int textVersion) {
    try {
      if (resources == null || textVersion <= 0)
        throw new IllegalStateException("Summary resources or version invalid");
      var fault = resources.tokenizer().prepare(faultText);
      var action = resources.tokenizer().prepare(actionText);
      var results = new LinkedHashMap<String, Map<String, Object>>();
      for (String algorithm : List.of("TFIDF", "TEXTRANK"))
        results.put(algorithm, generatePrepared(algorithm, fault, action, textVersion));
      return results;
    } catch (RuntimeException error) {
      LOG.error("摘要生成失败", error);
      throw new ApiException(503, "SUMMARY_UNAVAILABLE", "摘要生成暂不可用，请稍后重试");
    }
  }

  // The offline evaluation times one complete strategy, including the same preprocessing used
  // online.
  Map<String, Object> generateOne(
      String algorithm, String faultText, String actionText, int textVersion) {
    return generatePrepared(
        algorithm,
        resources.tokenizer().prepare(faultText),
        resources.tokenizer().prepare(actionText),
        textVersion);
  }

  private Map<String, Object> generatePrepared(
      String algorithm, ChineseText.Field fault, ChineseText.Field action, int version) {
    boolean tfidf = "TFIDF".equals(algorithm);
    var result = new LinkedHashMap<String, Object>();
    result.put("algorithm", algorithm);
    result.put("algorithmVersion", tfidf ? "tfidf-v1" : "textrank-v1");
    result.put("dictionaryVersion", resources.idf().dictionaryVersion());
    result.put("idfVersion", tfidf ? resources.idf().version() : null);
    result.put("textVersion", version);
    result.put("fault", score(fault, tfidf));
    result.put("action", score(action, tfidf));
    return result;
  }

  private Map<String, Object> score(ChineseText.Field field, boolean tfidf) {
    return TermWeights.select(
        field, tfidf ? TermWeights.tfidf(field, resources.idf()) : TermWeights.textRank(field));
  }

  public void validate(
      Map<String, Object> summary,
      String expectedAlgorithm,
      int textVersion,
      String faultText,
      String actionText) {
    if (summary == null
        || !Set.of("TFIDF", "TEXTRANK").contains(expectedAlgorithm)
        || !expectedAlgorithm.equals(summary.get("algorithm"))
        || textVersion < 1
        || !integer(summary.get("textVersion"), textVersion)
        || !versionLabel(summary.get("algorithmVersion"))
        || !versionLabel(summary.get("dictionaryVersion"))
        || !summary.containsKey("idfVersion")
        || ("TFIDF".equals(expectedAlgorithm)
            ? !versionLabel(summary.get("idfVersion"))
            : summary.get("idfVersion") != null)
        || !validField(summary.get("fault"), faultText)
        || !validField(summary.get("action"), actionText))
      throw new ApiException(500, "SUMMARY_DATA_INCONSISTENT", "当前工单的摘要资料不完整，请重新加载。");
  }

  private static boolean versionLabel(Object value) {
    return value instanceof String s && !s.isBlank() && s.length() <= 64;
  }

  private static boolean integer(Object value, int expected) {
    return (value instanceof Integer || value instanceof Long)
        && ((Number) value).longValue() == expected;
  }

  private static boolean validField(Object value, String raw) {
    if (!(value instanceof Map<?, ?> f)
        || !f.keySet().containsAll(List.of("sentence", "start", "end", "keywords", "selection"))
        || !(f.get("selection") instanceof String method)
        || !(f.get("keywords") instanceof List<?> words)
        || words.size() > 5) return false;
    var seen = new HashSet<String>();
    for (Object word : words)
      if (!(word instanceof String s)
          || ChineseText.blank(s)
          || !seen.add(s.toLowerCase(Locale.ROOT))) return false;
    if ("EMPTY".equals(method))
      return f.get("sentence") == null
          && f.get("start") == null
          && f.get("end") == null
          && words.isEmpty()
          && (raw == null || ChineseText.blank(raw));
    if (!Set.of("SINGLE", "RANKED", "FALLBACK").contains(method)
        || words.isEmpty() != method.equals("FALLBACK")
        || !(f.get("sentence") instanceof String text)
        || ChineseText.blank(text)
        || !(f.get("start") instanceof Number a)
        || !(f.get("end") instanceof Number b)) return false;
    int start = a.intValue(), end = b.intValue();
    return integer(a, start)
        && integer(b, end)
        && start >= 0
        && end > start
        && end - start == text.length()
        && (raw == null
            || (end <= raw.length() && raw.regionMatches(start, text, 0, text.length())));
  }
}
