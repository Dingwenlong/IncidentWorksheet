package cn.workorders.orders;

import cn.workorders.common.ApiException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;

record OrderFilter(String predicate, List<Object> args, int page, int size) {
  private static final Set<String> PARAMS =
      Set.of(
          "keyword",
          "equipmentId",
          "status",
          "creatorId",
          "dateFrom",
          "dateTo",
          "page",
          "pageSize");
  private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "COMPLETED");
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final LocalDateTime MIN_MYSQL = LocalDate.of(1000, 1, 1).atStartOfDay();

  static OrderFilter parse(MultiValueMap<String, String> query) {
    query.forEach(
        (k, v) -> {
          if (!PARAMS.contains(k) || v.size() != 1) throw ApiException.invalid(k, "未知或重复的查询条件");
        });
    List<String> filters = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    String kw = OrderInput.trim(query.getFirst("keyword") == null ? "" : query.getFirst("keyword"));
    if (kw.codePointCount(0, kw.length()) > 200)
      throw ApiException.invalid("keyword", "关键词最多 200 个字符");
    if (!kw.isEmpty()) {
      String like = "%" + kw.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
      List<String> matches = new ArrayList<>();
      for (String col :
          List.of(
              "order_no",
              "title",
              "equipment_code",
              "equipment_name",
              "fault_text",
              "action_text")) {
        matches.add(
            "CONVERT(o." + col + " USING utf8mb4) COLLATE utf8mb4_0900_ai_ci LIKE ? ESCAPE '!'");
        values.add(like);
      }
      filters.add("(" + String.join(" OR ", matches) + ")");
    }
    if (query.containsKey("equipmentId")) {
      filters.add("o.equipment_id=?");
      values.add(OrderInput.id(query.getFirst("equipmentId"), "equipmentId"));
    }
    if (query.containsKey("creatorId")) {
      filters.add("o.creator_id=?");
      values.add(OrderInput.id(query.getFirst("creatorId"), "creatorId"));
    }
    String status = query.getFirst("status");
    if (status != null) {
      if (!STATUSES.contains(status)) throw ApiException.invalid("status", "工单状态无效");
      filters.add("o.status=?");
      values.add(status);
    }
    LocalDate from = date(query.getFirst("dateFrom"), "dateFrom");
    LocalDate to = date(query.getFirst("dateTo"), "dateTo");
    if (from != null && to != null && from.isAfter(to))
      throw ApiException.invalid("dateTo", "结束日期不得早于开始日期");
    if (from != null && !utcStart(from).isBefore(MIN_MYSQL)) {
      filters.add("o.created_at>=?");
      values.add(utcStart(from));
    }
    if (to != null) {
      LocalDateTime end = utcStart(to.plusDays(1));
      if (end.isAfter(MIN_MYSQL)) {
        filters.add("o.created_at<?");
        values.add(end);
      } else filters.add("1=0");
    }
    return new OrderFilter(
        filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters),
        List.copyOf(values),
        number(query, "page", 1, Integer.MAX_VALUE),
        number(query, "pageSize", 20, 100));
  }

  private static LocalDateTime utcStart(LocalDate d) {
    return LocalDateTime.ofInstant(d.atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
  }

  private static int number(
      MultiValueMap<String, String> query, String key, int defaultValue, int max) {
    if (!query.containsKey(key)) return defaultValue;
    String value = query.getFirst(key);
    if (value != null && value.matches("[1-9][0-9]{0,9}")) {
      try {
        int parsed = Integer.parseInt(value);
        if (parsed <= max) return parsed;
      } catch (NumberFormatException ignored) {
        /* exceeds integer range */
      }
    }
    throw ApiException.invalid(key, "页码或页大小超出有效范围");
  }

  private static LocalDate date(String value, String key) {
    if (value == null) return null;
    if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !value.startsWith("0000")) {
      try {
        return LocalDate.parse(value);
      } catch (DateTimeException ignored) {
        /* invalid date */
      }
    }
    throw ApiException.invalid(key, "请使用有效日期 YYYY-MM-DD");
  }
}
