package cn.workorders.nlp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rebuilds the frozen IDF JSON exclusively from the development split; evaluation labels are never
 * opened.
 */
public class SnapshotBuilder {
  static List<String> lines(String resource) throws IOException {
    try (var in = SnapshotBuilder.class.getResourceAsStream("/nlp/" + resource)) {
      if (in == null) throw new IOException("Missing resource " + resource);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  static SortedMap<String, Integer> frequencies(ChineseText tokenizer, List<String> rows) {
    var df = new TreeMap<String, Integer>(SnapshotBuilder::compareCodePoints);
    var ids = new HashSet<String>();
    for (String row : rows) {
      String[] cells = row.split("\t", -1);
      if (cells.length != 3 || !ids.add(cells[0]) || cells[1].isBlank())
        throw new IllegalArgumentException("Invalid development corpus record");
      var terms = new HashSet<>(tokenizer.prepare(cells[1]).display().keySet());
      terms.addAll(tokenizer.prepare(cells[2]).display().keySet());
      for (String term : terms) df.merge(term, 1, Integer::sum);
    }
    return df;
  }

  private static int compareCodePoints(String a, String b) {
    return Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());
  }

  static String snapshot() throws IOException {
    var data = lines("development.tsv");
    if (data.size() != 61) throw new IllegalArgumentException("Expected 60 development records");
    var df =
        frequencies(
            new ChineseText(lines("domain.txt"), lines("stopwords.txt")),
            data.subList(1, data.size()));
    String entries =
        df.entrySet().stream()
            .map(e -> "    \"" + e.getKey() + "\": " + e.getValue())
            .collect(Collectors.joining(",\n"));
    return "{\n"
               + "  \"version\": \"idf-v1\",\n"
               + "  \"dictionaryVersion\": \"dict-v1\",\n"
               + "  \"N\": 60,\n"
               + "  \"df\": {\n"
        + entries
        + "\n  }\n}\n";
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) throw new IllegalArgumentException("Expected output path for IDF JSON");
    Files.writeString(Path.of(args[0]), snapshot(), StandardCharsets.UTF_8);
  }
}
