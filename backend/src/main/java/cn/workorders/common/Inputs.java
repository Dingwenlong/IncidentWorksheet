package cn.workorders.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

public final class Inputs {
  private Inputs() {}

  private static final Pattern EDGE_SPACE = Pattern.compile("(?U)^\\s+|\\s+$");

  public static String trim(String value) {
    return EDGE_SPACE.matcher(value).replaceAll("");
  }

  public static void fields(JsonNode obj, String... allowed) {
    if (obj == null || !obj.isObject()) throw ApiException.invalid("body", "请提供 JSON 对象");
    var names = Set.of(allowed);
    obj.fieldNames()
        .forEachRemaining(
            k -> {
              if (!names.contains(k)) throw ApiException.invalid(k, "不支持该字段");
            });
  }

  public static String string(JsonNode obj, String key, boolean trim, int min, int max) {
    var n = obj.path(key);
    if (!n.isTextual()) throw ApiException.invalid(key, "请填写文本");
    String value = trim ? trim(n.asText()) : n.asText();
    int length = value.codePointCount(0, value.length());
    if (length < min || length > max)
      throw ApiException.invalid(key, "长度应为 " + min + " 至 " + max + " 个字符");
    return value;
  }

  public static String choice(String key, String value, String... choices) {
    if (!Arrays.asList(choices).contains(value)) throw ApiException.invalid(key, "取值无效");
    return value;
  }

  public static int version(JsonNode obj) {
    var n = obj.path("version");
    if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 1)
      throw ApiException.invalid("version", "需要正整数版本");
    return n.intValue();
  }

  public static long id(String str) {
    try {
      if (!str.matches("[1-9][0-9]*")) throw new NumberFormatException();
      return Long.parseLong(str);
    } catch (NumberFormatException ex) {
      throw ApiException.invalid("id", "无效的编号");
    }
  }

  public static String username(String raw) {
    String s = trim(raw);
    if (!s.matches("[A-Za-z0-9_]{4,32}"))
      throw ApiException.invalid("username", "用户名为4至32位字母、数字或下划线");
    return s.toLowerCase(Locale.ROOT);
  }

  public static String code(String s) {
    StringBuilder out = new StringBuilder();
    s.chars().forEach(c -> out.append((char) (c >= 'a' && c <= 'z' ? c - 32 : c)));
    return out.toString();
  }

  public static String like(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }
}
