package cn.workorders.orders;

import cn.workorders.auth.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OrderReadService {
  private final JdbcTemplate db;
  private final OrderStorage storage;

  OrderReadService(JdbcTemplate db, OrderStorage storage) {
    this.db = db;
    this.storage = storage;
  }

  public Map<String, Object> list(OrderFilter filter) {
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM work_order o" + filter.predicate(),
            Long.class,
            filter.args().toArray());
    long offset = (filter.page() - 1L) * filter.size();
    List<Map<String, Object>> items = List.of();
    if (offset < total) {
      String sql =
          "SELECT "
              + OrderRow.COLUMNS
              + ","
              + OrderStorage.SUMMARY_COLUMNS
              + " FROM work_order o LEFT JOIN sys_user u ON u.id=o.creator_id"
              + " LEFT JOIN work_order_summary s ON s.order_id=o.id AND s.algorithm='TFIDF'"
              + filter.predicate()
              + " ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?";
      List<Object> params = new ArrayList<>(filter.args());
      params.add(filter.size());
      params.add(offset);
      items =
          db.query(
              sql,
              (rs, i) -> {
                Map<String, Object> item = OrderRow.listItem(rs);
                Map<String, Object> summary =
                    storage.storedSummary(
                        rs, rs.getString("id"), "TFIDF", rs.getInt("text_version"), null, null);
                Map<String, Object> card = new LinkedHashMap<>();
                for (String field : List.of("fault", "action"))
                  card.put(field, ((Map<?, ?>) summary.get(field)).get("sentence"));
                item.put("summary", card);
                return item;
              },
              params.toArray());
    }
    return Map.of("page", filter.page(), "pageSize", filter.size(), "total", total, "items", items);
  }

  public Map<String, Object> detail(long id, CurrentUser user) {
    OrderRow row = storage.order(id);
    boolean own =
        "TECHNICIAN".equals(user.role()) && row.creatorId().equals(Long.toString(user.id()));
    Map<String, Boolean> permissions =
        Map.of(
            "canEdit",
            own && !"COMPLETED".equals(row.status()),
            "canStart",
            own && "PENDING".equals(row.status()),
            "canComplete",
            own && "PROCESSING".equals(row.status()));
    List<Map<String, Object>> results =
        db.query(
            "SELECT "
                + OrderStorage.SUMMARY_COLUMNS
                + " FROM work_order_summary s WHERE s.order_id=?",
            (rs, i) ->
                storage.storedSummary(
                    rs,
                    row.id(),
                    rs.getString("algorithm"),
                    row.textVersion(),
                    row.faultText(),
                    row.actionText()),
            id);
    Map<String, Object> summaries = new LinkedHashMap<>();
    for (Map<String, Object> result : results)
      summaries.put((String) result.get("algorithm"), result);
    if (results.size() != 2 || !summaries.keySet().containsAll(OrderStorage.ALGORITHMS))
      throw OrderStorage.inconsistent(row.id());
    List<Map<String, Object>> events =
        db.query(
            """
SELECT id,type,from_status,to_status,actor_id,actor_name,occurred_at FROM work_order_event
WHERE order_id=? ORDER BY occurred_at,id
""",
            (rs, i) -> event(rs),
            id);
    return Map.of(
        "order", row, "permissions", permissions, "summaries", summaries, "events", events);
  }

  private static Map<String, Object> event(ResultSet rs) throws SQLException {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("id", rs.getString("id"));
    event.put("type", rs.getString("type"));
    event.put("fromStatus", rs.getString("from_status"));
    event.put("toStatus", rs.getString("to_status"));
    event.put("actorId", rs.getString("actor_id"));
    event.put("actorName", rs.getString("actor_name"));
    event.put("occurredAt", OrderRow.timestamp(rs, "occurred_at"));
    return event;
  }
}
