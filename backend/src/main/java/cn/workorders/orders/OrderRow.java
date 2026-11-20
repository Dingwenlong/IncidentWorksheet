package cn.workorders.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

public record OrderRow(
    String id,
    String orderNo,
    String equipmentId,
    String equipmentCode,
    String equipmentName,
    String title,
    String faultText,
    String actionText,
    String status,
    String creatorId,
    String creatorName,
    int version,
    int textVersion,
    String createdAt,
    String updatedAt) {
  private static final DateTimeFormatter UTC =
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
  static final String COLUMNS =
      """
      o.id,o.order_no,o.equipment_id,o.equipment_code,o.equipment_name,o.title,o.status,
      o.creator_id,u.display_name,o.version,o.text_version,o.created_at,o.updated_at
      """;

  static String timestamp(ResultSet rs, String key) throws SQLException {
    return UTC.format(rs.getObject(key, LocalDateTime.class).toInstant(ZoneOffset.UTC));
  }

  static OrderRow read(ResultSet rs) throws SQLException {
    return new OrderRow(
        rs.getString("id"),
        rs.getString("order_no"),
        rs.getString("equipment_id"),
        rs.getString("equipment_code"),
        rs.getString("equipment_name"),
        rs.getString("title"),
        rs.getString("fault_text"),
        rs.getString("action_text"),
        rs.getString("status"),
        rs.getString("creator_id"),
        rs.getString("display_name"),
        rs.getInt("version"),
        rs.getInt("text_version"),
        timestamp(rs, "created_at"),
        timestamp(rs, "updated_at"));
  }

  static Map<String, Object> listItem(ResultSet rs) throws SQLException {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", rs.getString("id"));
    result.put("orderNo", rs.getString("order_no"));
    result.put("equipmentId", rs.getString("equipment_id"));
    result.put("equipmentCode", rs.getString("equipment_code"));
    result.put("equipmentName", rs.getString("equipment_name"));
    result.put("title", rs.getString("title"));
    result.put("status", rs.getString("status"));
    result.put("creatorId", rs.getString("creator_id"));
    result.put("creatorName", rs.getString("display_name"));
    result.put("version", rs.getInt("version"));
    result.put("textVersion", rs.getInt("text_version"));
    result.put("createdAt", timestamp(rs, "created_at"));
    result.put("updatedAt", timestamp(rs, "updated_at"));
    return result;
  }
}
