package cn.workorders.nlp;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import java.util.*;
import java.util.regex.*;

/** Token offsets and sentence boundaries are always indexed into the original Java string. */
final class ChineseText {
  record Sentence(String text, int start, int end, List<String> tokens) {}

  record Field(List<Sentence> sentences, Map<String, String> display) {}

  private static final Pattern RUN =
      Pattern.compile("[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*|\\p{IsHan}+");
  private static final Pattern WHITE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern SEGMENTS = Pattern.compile("[^。！？!?；;\\r\\n]+[。！？!?；;]*|[。！？!?；;]+");
  private static final Set<String> NEGATIONS = Set.of("未", "无", "不", "未恢复", "未启动");
  private final List<String> domain;
  private final Set<String> stop;
  private final JiebaSegmenter jieba = new JiebaSegmenter();

  ChineseText(Collection<String> domain, Collection<String> stop) {
    this.domain =
        domain.stream()
            .map(String::strip)
            .filter(word -> !word.isEmpty())
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    this.stop =
        Set.copyOf(stop.stream().map(String::strip).filter(word -> !word.isEmpty()).toList());
  }

  static boolean blank(String text) {
    return text == null || text.isEmpty() || WHITE.matcher(text).matches();
  }

  Field prepare(String raw) {
    var candidates = new ArrayList<Sentence>();
    var display = new LinkedHashMap<String, String>();
    Sentence firstPunctuation = null;
    var matches = SEGMENTS.matcher(raw == null ? "" : raw);
    while (matches.find()) {
      int begin = matches.start(), end = matches.end();
      while (begin < end && whitespace(raw.codePointAt(begin)))
        begin += Character.charCount(raw.codePointAt(begin));
      while (end > begin && whitespace(raw.codePointBefore(end)))
        end -= Character.charCount(raw.codePointBefore(end));
      if (begin == end) continue;
      String sentence = raw.substring(begin, end);
      if (sentence.codePoints().allMatch(c -> "。！？!?；;".indexOf(c) >= 0)) {
        if (firstPunctuation == null)
          firstPunctuation = new Sentence(sentence, begin, end, List.of());
        continue;
      }
      var tokens = new ArrayList<String>();
      var runs = RUN.matcher(sentence);
      while (runs.find()) {
        String part = runs.group();
        if (part.charAt(0) < 128) add(part, tokens, display);
        else tokenizeHan(part, tokens, display);
      }
      candidates.add(new Sentence(sentence, begin, end, List.copyOf(tokens)));
    }
    if (candidates.isEmpty() && firstPunctuation != null) candidates.add(firstPunctuation);
    return new Field(List.copyOf(candidates), Collections.unmodifiableMap(display));
  }

  private static boolean whitespace(int cp) {
    return Character.isSpaceChar(cp) || cp == 0x85 || (cp >= 9 && cp <= 13);
  }

  private void tokenizeHan(String text, List<String> tokens, Map<String, String> display) {
    int start = 0, i = 0;
    while (i < text.length()) {
      String match = null;
      for (String word : domain) {
        if (text.startsWith(word, i)) {
          match = word;
          break;
        }
      }
      if (match == null) {
        i += Character.charCount(text.codePointAt(i));
        continue;
      }
      segment(text.substring(start, i), tokens, display);
      add(match, tokens, display);
      start = i += match.length();
    }
    segment(text.substring(start), tokens, display);
  }

  private void segment(String text, List<String> tokens, Map<String, String> display) {
    if (text.isEmpty()) return;
    for (SegToken token : jieba.process(text, JiebaSegmenter.SegMode.SEARCH)) {
      String original = text.substring(token.startOffset, token.endOffset);
      if (!original.isEmpty()) add(original, tokens, display);
    }
  }

  private void add(String original, List<String> tokens, Map<String, String> display) {
    String key = original.toLowerCase(Locale.ROOT);
    if (stop.contains(key) && !NEGATIONS.contains(key)) return;
    display.putIfAbsent(key, original);
    tokens.add(key);
  }
}
