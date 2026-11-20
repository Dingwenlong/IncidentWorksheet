package cn.workorders.orders;

import cn.workorders.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Parse the JSON without coercing numbers into ID strings or nulls into empty originals. */
final class OrderInput {
  private static final Pattern KEY =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
  private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern WHITE = Pattern.compile("(?U)^\\s+|\\s+$");

  record NewOrder(
      long equipmentId,
      String title,
      String faultText,
      String actionText,
      String key,
      byte[] hash) {}

  enum Command {
    EDIT,
    START,
    COMPLETE
  }

  record Change(Command command, int version, String title, String faultText, String actionText) {}

  static NewOrder create(JsonNode body, String key) {
    fields(body, Set.of("equipmentId", "title", "faultText", "actionText"));
    if (key == null || !KEY.matcher(key).matches()) {
      throw ApiException.invalid("Idempotency-Key", "创建工单需要有效的 UUIDv4 请求标识");
    }
    long equipment = id(text(body, "equipmentId"), "equipmentId");
    String title = title(body);
    String fault = original(body, "faultText", true);
    String action = body.has("actionText") ? original(body, "actionText", false) : "";
    byte[] hash = creationHash(List.of(Long.toString(equipment), title, fault, action));
    return new NewOrder(equipment, title, fault, action, key.toLowerCase(Locale.ROOT), hash);
  }

  static Change edit(JsonNode body) {
    fields(body, Set.of("version", "title", "faultText", "actionText"));
    if (!body.has("title") && !body.has("faultText") && !body.has("actionText")) {
      throw ApiException.invalid("body", "请至少提交一个可编辑字段");
    }
    return change(Command.EDIT, body);
  }

  static Change transition(JsonNode body) {
    object(body);
    String value = text(body, "action");
    if ("START".equals(value)) {
      fields(body, Set.of("action", "version"));
      return change(Command.START, body);
    }
    if ("COMPLETE".equals(value)) {
      fields(body, Set.of("action", "version", "title", "faultText", "actionText"));
      return change(Command.COMPLETE, body);
    }
    throw ApiException.invalid("action", "请选择开始处理或完成工单");
  }

  private static Change change(Command command, JsonNode body) {
    JsonNode version = body.path("version");
    if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() <= 0) {
      throw ApiException.invalid("version", "记录版本必须为正整数");
    }
    return new Change(
        command,
        version.intValue(),
        body.has("title") ? title(body) : null,
        body.has("faultText") ? original(body, "faultText", true) : null,
        body.has("actionText") ? original(body, "actionText", false) : null);
  }

  static long id(String value, String name) {
    if (value != null && ID.matcher(value).matches()) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException ignored) {
        /* exceeds BIGINT */
      }
    }
    throw ApiException.invalid(name, "编号必须为正十进制 BIGINT 字符串");
  }

  static String trim(String s) {
    return WHITE.matcher(s).replaceAll("");
  }

  private static String title(JsonNode body) {
    String value = trim(text(body, "title"));
    if (value.isEmpty() || value.codePointCount(0, value.length()) > 100) {
      throw ApiException.invalid("title", "请输入 1 至 100 个字符的标题");
    }
    return value;
  }

  private static String original(JsonNode body, String name, boolean required) {
    String value = text(body, name);
    if (value.codePointCount(0, value.length()) > 5000) {
      throw ApiException.invalid(name, "原文最多 5000 个字符");
    }
    if (!trim(value).isEmpty()) return value;
    if (required) throw ApiException.invalid(name, "请填写故障描述");
    return "";
  }

  private static String text(JsonNode obj, String name) {
    JsonNode value = obj.path(name);
    if (!value.isTextual()) throw ApiException.invalid(name, "请提供文本值");
    String s = value.textValue();
    if (s.codePoints().anyMatch(n -> n >= 0xd800 && n <= 0xdfff)) {
      throw ApiException.invalid(name, "文本含无效的 Unicode 字符");
    }
    return s;
  }

  private static void object(JsonNode body) {
    if (body == null || !body.isObject()) throw ApiException.invalid("body", "请提供 JSON 对象");
  }

  private static void fields(JsonNode body, Set<String> allowed) {
    object(body);
    body.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw ApiException.invalid(name, "不允许提交此字段");
            });
  }

  // RFC 8785 string-array subset. Non-control Unicode characters retain their original encoding.
  static byte[] creationHash(List<String> fields) {
    StringBuilder json = new StringBuilder("[");
    for (String field : fields) {
      if (json.length() > 1) json.append(',');
      json.append('"');
      field
          .codePoints()
          .forEach(
              cp -> {
                switch (cp) {
                  case '"' -> json.append("\\\"");
                  case '\\' -> json.append("\\\\");
                  case '\b' -> json.append("\\b");
                  case '\t' -> json.append("\\t");
                  case '\n' -> json.append("\\n");
                  case '\f' -> json.append("\\f");
                  case '\r' -> json.append("\\r");
                  default -> {
                    if (cp < 0x20) json.append(String.format(Locale.ROOT, "\\u%04x", cp));
                    else json.appendCodePoint(cp);
                  }
                }
              });
      json.append('"');
    }
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(json.append(']').toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private OrderInput() {}
}
