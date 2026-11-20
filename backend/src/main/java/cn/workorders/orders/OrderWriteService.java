package cn.workorders.orders;

import cn.workorders.auth.CurrentUser;
import cn.workorders.common.ApiException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderWriteService {
  record Created(boolean first, OrderRow order) {}

  private record Existing(long id, byte[] hash) {}

  private record Equipment(String code, String name, String status) {}

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate db;
  private final OrderStorage storage;
  private final TransactionTemplate createTx;

  OrderWriteService(JdbcTemplate db, OrderStorage storage, PlatformTransactionManager tm) {
    this.db = db;
    this.storage = storage;
    this.createTx = new TransactionTemplate(tm);
    this.createTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  Created create(CurrentUser user, OrderInput.NewOrder input) {
    // A concurrent creation key or a random order number can win the unique index.
    // Roll back that attempt before looking up the winner or generating another number.
    for (int attempt = 0; attempt < 5; attempt++) {
      try {
        return createTx.execute(status -> createOnce(user, input));
      } catch (DuplicateKeyException e) {
        if (attempt == 4) throw e;
      }
    }
    throw new IllegalStateException("Order creation retry exhausted");
  }

  private Created createOnce(CurrentUser user, OrderInput.NewOrder in) {
    Created replay = previous(user.id(), in);
    if (replay != null) return replay;
    List<Equipment> rows =
        db.query(
            "SELECT code,name,status FROM equipment WHERE id=? FOR UPDATE",
            (rs, i) ->
                new Equipment(rs.getString("code"), rs.getString("name"), rs.getString("status")),
            in.equipmentId());
    // Under READ COMMITTED this second query sees an earlier writer that held the equipment lock.
    replay = previous(user.id(), in);
    if (replay != null) return replay;
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "未找到设备");
    Equipment eq = rows.get(0);
    if (!"ACTIVE".equals(eq.status()))
      throw new ApiException(409, "EQUIPMENT_DISABLED", "设备已停用，请重新选择");
    Instant clock = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    LocalDateTime time = LocalDateTime.ofInstant(clock, ZoneOffset.UTC);
    byte[] suffix = new byte[4];
    RANDOM.nextBytes(suffix);
    String number =
        "WO-"
            + clock.atZone(SHANGHAI).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-"
            + HexFormat.of().withUpperCase().formatHex(suffix);
    db.update(
        """
INSERT INTO work_order(order_no,equipment_id,equipment_code,equipment_name,title,fault_text,action_text,
    status,creator_id,version,text_version,created_at,updated_at,creation_key,creation_hash)
VALUES(?,?,?,?,?,?,?,'PENDING',?,1,1,?,?,?,?)
""",
        number,
        in.equipmentId(),
        eq.code(),
        eq.name(),
        in.title(),
        in.faultText(),
        in.actionText(),
        user.id(),
        time,
        time,
        in.key(),
        in.hash());
    long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    storage.saveSummaries(id, in.faultText(), in.actionText(), 1);
    addEvent(id, "CREATED", null, "PENDING", user, time);
    return new Created(true, storage.order(id));
  }

  private Created previous(long creator, OrderInput.NewOrder in) {
    List<Existing> hits =
        db.query(
            "SELECT id,creation_hash FROM work_order WHERE creator_id=? AND creation_key=?",
            (rs, row) -> new Existing(rs.getLong("id"), rs.getBytes("creation_hash")),
            creator,
            in.key());
    if (hits.isEmpty()) return null;
    Existing existing = hits.get(0);
    if (!MessageDigest.isEqual(existing.hash(), in.hash())) {
      throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "该请求标识已用于不同的工单内容");
    }
    return new Created(false, storage.order(existing.id()));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public OrderRow change(long id, CurrentUser user, OrderInput.Change edit) {
    List<Long> locked =
        db.queryForList("SELECT id FROM work_order WHERE id=? FOR UPDATE", Long.class, id);
    if (locked.isEmpty()) throw new ApiException(404, "NOT_FOUND", "未找到工单");
    OrderRow old = storage.order(id);
    if (!old.creatorId().equals(Long.toString(user.id()))) {
      throw new ApiException(403, "FORBIDDEN", "仅能处理本人创建的工单");
    }
    if (old.version() != edit.version())
      throw new ApiException(409, "VERSION_CONFLICT", "工单已被更新，请重新加载");
    String next =
        switch (edit.command()) {
          case EDIT -> {
            if ("COMPLETED".equals(old.status())) throw stateConflict();
            yield old.status();
          }
          case START -> {
            if (!"PENDING".equals(old.status())) throw stateConflict();
            yield "PROCESSING";
          }
          case COMPLETE -> {
            if (!"PROCESSING".equals(old.status())) throw stateConflict();
            yield "COMPLETED";
          }
        };
    String title = edit.title() == null ? old.title() : edit.title();
    String fault = edit.faultText() == null ? old.faultText() : edit.faultText();
    String action = edit.actionText() == null ? old.actionText() : edit.actionText();
    if ("COMPLETED".equals(next) && action.isEmpty()) {
      throw ApiException.invalid("actionText", "完成工单前请填写处理经过");
    }
    boolean textChanged = !old.faultText().equals(fault) || !old.actionText().equals(action);
    if (!textChanged && old.title().equals(title) && old.status().equals(next)) return old;
    LocalDateTime time = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    int textVersion = old.textVersion() + (textChanged ? 1 : 0);
    if (textChanged) storage.saveSummaries(id, fault, action, textVersion);
    db.update(
        """
        UPDATE work_order SET title=?,fault_text=?,action_text=?,status=?,version=version+1,
        text_version=?,updated_at=? WHERE id=?
        """,
        title,
        fault,
        action,
        next,
        textVersion,
        time,
        id);
    if (!old.status().equals(next)) {
      addEvent(
          id,
          edit.command() == OrderInput.Command.START ? "STARTED" : "COMPLETED",
          old.status(),
          next,
          user,
          time);
    }
    return storage.order(id);
  }

  private static ApiException stateConflict() {
    return new ApiException(409, "ORDER_STATE_CONFLICT", "当前状态不允许该操作，请重新加载");
  }

  private void addEvent(
      long id, String type, String before, String after, CurrentUser actor, LocalDateTime time) {
    db.update(
        """
INSERT INTO work_order_event(order_id,type,from_status,to_status,actor_id,actor_name,occurred_at)
VALUES(?,?,?,?,?,?,?)
""",
        id,
        type,
        before,
        after,
        actor.id(),
        actor.displayName(),
        time);
  }
}
