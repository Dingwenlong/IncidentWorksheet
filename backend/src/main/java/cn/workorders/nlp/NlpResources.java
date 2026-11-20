package cn.workorders.nlp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaban.analysis.jieba.JiebaSegmenter;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

record NlpResources(ChineseText tokenizer, TermWeights.Idf idf) {
  static final String DICTIONARY = "dict-v1", IDF = "idf-v1";
  static final ObjectMapper JSON =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  static NlpResources load() throws Exception {
    var manifest = new Properties();
    try (var in = open("manifest.properties")) {
      manifest.load(in);
    }
    if (!DICTIONARY.equals(manifest.getProperty("dictionaryVersion"))
        || !IDF.equals(manifest.getProperty("idfVersion"))
        || !"com.huaban:jieba-analysis:1.0.2".equals(manifest.getProperty("jiebaCoordinate")))
      throw new IOException("NLP manifest version mismatch");
    URL cls = JiebaSegmenter.class.getResource("JiebaSegmenter.class");
    if (cls == null || !(cls.openConnection() instanceof JarURLConnection jar))
      throw new IOException("Jieba release archive unavailable");
    try (var source = jar.getJarFileURL().openStream()) {
      verify(manifest, "jiebaSha256", digest(source.readAllBytes()));
    }
    var dictionary = lines(checked(manifest, "domain.txt"));
    var stops = lines(checked(manifest, "stopwords.txt"));
    var snap = JSON.readTree(checked(manifest, "idf.json"));
    if (!IDF.equals(snap.path("version").asText())
        || !DICTIONARY.equals(snap.path("dictionaryVersion").asText())
        || !snap.path("N").isInt()
        || snap.path("N").intValue() != 60
        || !snap.path("df").isObject()
        || snap.path("df").isEmpty()) throw new IOException("Invalid frozen IDF snapshot");
    var df = new TreeMap<String, Integer>();
    for (var it = snap.path("df").fields(); it.hasNext(); ) {
      var e = it.next();
      if (!e.getValue().isInt()) throw new IOException("Non-integral document frequency");
      df.put(e.getKey(), e.getValue().intValue());
    }
    return new NlpResources(
        new ChineseText(dictionary, stops), new TermWeights.Idf(IDF, DICTIONARY, 60, df));
  }

  private static InputStream open(String name) throws IOException {
    InputStream input = NlpResources.class.getResourceAsStream("/nlp/" + name);
    if (input == null) throw new IOException("Missing NLP resource: " + name);
    return input;
  }

  private static byte[] checked(Properties manifest, String name) throws Exception {
    try (var input = open(name)) {
      byte[] bytes = input.readAllBytes();
      verify(manifest, name + ".sha256", digest(bytes));
      return bytes;
    }
  }

  private static void verify(Properties properties, String key, String digest) throws IOException {
    if (!digest.equals(properties.getProperty(key)))
      throw new IOException("Resource checksum mismatch: " + key);
  }

  private static String digest(byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  static List<String> lines(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8)
        .lines()
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
