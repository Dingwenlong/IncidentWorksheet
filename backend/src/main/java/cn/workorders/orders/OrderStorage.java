package cn.workorders.orders;

import cn.workorders.common.ApiException;
import cn.workorders.nlp.SummaryEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OrderStorage {
  private static final Logger log = LoggerFactory.getLogger(OrderStorage.class);
  static final List<String> ALGORITHMS = List.of("TFIDF", "TEXTRANK");
  static final String SUMMARY_COLUMNS =
      """
      s.algorithm,s.text_version AS summary_version,s.algorithm_version,
      s.dictionary_version,s.idf_version,s.fault,s.action
      """;
  private static final TypeReference<Map<String, Object>> FIELD = new TypeReference<>() {};
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final SummaryEngine engine;

  OrderStorage(JdbcTemplate db, ObjectMapper json, SummaryEngine engine) {
    this.db = db;
    this.json = json;
    this.engine = engine;
  }

  OrderRow order(long id) {
    List<OrderRow> rows =
        db.query(
            "SELECT "
                + OrderRow.COLUMNS
                + ",o.fault_text,o.action_text "
                + "FROM work_order o JOIN sys_user u ON u.id=o.creator_id WHERE o.id=?",
            (rs, i) -> OrderRow.read(rs),
            id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "未找到工单");
    return rows.get(0);
  }

  Map<String, Object> storedSummary(
      ResultSet rs, String orderId, String expected, int wantedVersion, String fault, String action)
      throws SQLException {
    Map<String, Object> summary = new LinkedHashMap<>();
    String algorithm = rs.getString("algorithm");
    if (!ALGORITHMS.contains(algorithm == null ? "" : algorithm)) throw inconsistent(orderId);
    summary.put("algorithm", algorithm);
    summary.put("algorithmVersion", rs.getString("algorithm_version"));
    summary.put("dictionaryVersion", rs.getString("dictionary_version"));
    summary.put("idfVersion", rs.getString("idf_version"));
    summary.put("textVersion", rs.getInt("summary_version"));
    try {
      summary.put("fault", json.readValue(rs.getString("fault"), FIELD));
      summary.put("action", json.readValue(rs.getString("action"), FIELD));
      engine.validate(summary, expected, wantedVersion, fault, action);
    } catch (JsonProcessingException | RuntimeException ex) {
      throw inconsistent(orderId);
    }
    return summary;
  }

  static ApiException inconsistent(String id) {
    log.warn("Invalid or missing stored summary on work order {}", id);
    return new ApiException(500, "SUMMARY_DATA_INCONSISTENT", "当前工单的摘要资料不完整，请重新加载");
  }

  void saveSummaries(long orderId, String fault, String action, int textVersion) {
    Map<String, Map<String, Object>> pair = engine.generate(fault, action, textVersion);
    try {
      for (String algorithm : ALGORITHMS) {
        Map<String, Object> entry = pair.get(algorithm);
        engine.validate(entry, algorithm, textVersion, fault, action);
      }
    } catch (RuntimeException e) {
      throw new ApiException(503, "SUMMARY_UNAVAILABLE", "摘要生成暂不可用，请稍后重试");
    }
    // The caller holds the order lock. Replacement of both rows is atomic with the original text.
    db.update("DELETE FROM work_order_summary WHERE order_id=?", orderId);
    for (String algorithm : ALGORITHMS) {
      Map<String, Object> entry = pair.get(algorithm);
      try {
        db.update(
            """
            INSERT INTO work_order_summary(order_id,algorithm,text_version,algorithm_version,
              dictionary_version,idf_version,fault,action) VALUES(?,?,?,?,?,?,?,?)
            """,
            orderId,
            algorithm,
            textVersion,
            entry.get("algorithmVersion"),
            entry.get("dictionaryVersion"),
            entry.get("idfVersion"),
            json.writeValueAsString(entry.get("fault")),
            json.writeValueAsString(entry.get("action")));
      } catch (JsonProcessingException ex) {
        throw new ApiException(503, "SUMMARY_UNAVAILABLE", "摘要生成暂不可用，请稍后重试");
      }
    }
  }
}
