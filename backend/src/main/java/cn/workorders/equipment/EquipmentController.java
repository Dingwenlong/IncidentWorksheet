package cn.workorders.equipment;

import cn.workorders.auth.CurrentUser;
import cn.workorders.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {
  private final JdbcTemplate db;

  public EquipmentController(JdbcTemplate db) {
    this.db = db;
  }

  private static Map<String, Object> view(ResultSet r, int line) throws SQLException {
    var e = new LinkedHashMap<String, Object>();
    e.put("id", Long.toString(r.getLong("id")));
    e.put("code", r.getString("code"));
    e.put("name", r.getString("name"));
    e.put("location", r.getString("location"));
    e.put("status", r.getString("status"));
    e.put("version", r.getInt("version"));
    e.put("createdAt", DbPage.utc(r, "created_at"));
    e.put("updatedAt", DbPage.utc(r, "updated_at"));
    return e;
  }

  private String location(JsonNode body) {
    if (!body.hasNonNull("location")) return null;
    String s = Inputs.string(body, "location", true, 0, 200);
    return s.isEmpty() ? null : s;
  }

  private Map<String, Object> load(long id, boolean lock) {
    return db
        .query(
            "SELECT * FROM equipment WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            EquipmentController::view,
            id)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  @GetMapping
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    CurrentUser.require("TECHNICIAN", "SUPERVISOR");
    var where = new ArrayList<String>();
    var args = new ArrayList<Object>();
    if (code != null && !Inputs.trim(code).isEmpty()) {
      where.add("code LIKE ? ESCAPE '!'");
      args.add(Inputs.like(Inputs.code(Inputs.trim(code))));
    }
    if (name != null && !Inputs.trim(name).isEmpty()) {
      where.add("name LIKE ? ESCAPE '!'");
      args.add(Inputs.like(Inputs.trim(name)));
    }
    if (status != null) {
      where.add("status=?");
      args.add(Inputs.choice("status", status, "ACTIVE", "DISABLED"));
    }
    return DbPage.read(db, "equipment", where, args, EquipmentController::view, page, pageSize);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Map<String, Object> create(@RequestBody JsonNode body) {
    CurrentUser.require("SUPERVISOR");
    Inputs.fields(body, "code", "name", "location");
    String code = Inputs.code(Inputs.string(body, "code", true, 1, 40)),
        name = Inputs.string(body, "name", true, 1, 100),
        location = location(body);
    var key = new GeneratedKeyHolder();
    try {
      db.update(
          c -> {
            var p =
                c.prepareStatement(
                    "INSERT INTO equipment(code,name,location) VALUES(?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            p.setString(1, code);
            p.setString(2, name);
            p.setString(3, location);
            return p;
          },
          key);
    } catch (DuplicateKeyException ex) {
      throw new ApiException(409, "EQUIPMENT_CODE_EXISTS", "设备编号已存在");
    }
    return load(Objects.requireNonNull(key.getKey()).longValue(), false);
  }

  @PatchMapping("/{id}")
  @Transactional
  public Map<String, Object> edit(@PathVariable String id, @RequestBody JsonNode body) {
    CurrentUser.require("SUPERVISOR");
    Inputs.fields(body, "version", "name", "location");
    int version = Inputs.version(body);
    if (!body.has("name") && !body.has("location")) throw ApiException.invalid("body", "请指定要修改的字段");
    long key = Inputs.id(id);
    var current = load(key, true);
    if (!current.get("version").equals(version)) throw ApiException.conflict();
    String name =
        body.has("name") ? Inputs.string(body, "name", true, 1, 100) : (String) current.get("name");
    String loc = body.has("location") ? location(body) : (String) current.get("location");
    if (name.equals(current.get("name")) && Objects.equals(loc, current.get("location")))
      return current;
    db.update(
        "UPDATE equipment SET name=?,location=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE"
            + " id=?",
        name,
        loc,
        key);
    return load(key, false);
  }

  @PutMapping("/{id}/status")
  @Transactional
  public Map<String, Object> status(@PathVariable String id, @RequestBody JsonNode body) {
    CurrentUser.require("SUPERVISOR");
    Inputs.fields(body, "version", "status");
    int version = Inputs.version(body);
    long key = Inputs.id(id);
    var current = load(key, true);
    if (!current.get("version").equals(version)) throw ApiException.conflict();
    String status =
        Inputs.choice("status", Inputs.string(body, "status", false, 1, 8), "ACTIVE", "DISABLED");
    if (status.equals(current.get("status"))) return current;
    db.update(
        "UPDATE equipment SET status=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
        status,
        key);
    return load(key, false);
  }
}
