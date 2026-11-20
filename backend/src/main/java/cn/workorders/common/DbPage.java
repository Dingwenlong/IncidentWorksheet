package cn.workorders.common;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.*;

public final class DbPage {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'");

  private DbPage() {}

  public static String utc(ResultSet rs, String column) throws SQLException {
    return UTC.format(rs.getObject(column, LocalDateTime.class));
  }

  public static <T> Map<String, Object> read(
      JdbcTemplate db,
      String table,
      List<String> terms,
      List<Object> args,
      RowMapper<T> map,
      int page,
      int pageSize) {
    if (page < 1) throw ApiException.invalid("page", "页码须为正整数");
    if (pageSize < 1 || pageSize > 100) throw ApiException.invalid("pageSize", "每页1至100条");
    String from =
        " FROM " + table + (terms.isEmpty() ? "" : " WHERE " + String.join(" AND ", terms));
    Long total = db.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
    var params = new ArrayList<>(args);
    params.add(pageSize);
    params.add((long) (page - 1) * pageSize);
    var items =
        db.query("SELECT *" + from + " ORDER BY id DESC LIMIT ? OFFSET ?", map, params.toArray());
    return Map.of("items", items, "page", page, "pageSize", pageSize, "total", total);
  }
}
