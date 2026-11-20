package cn.workorders.accounts;

import cn.workorders.auth.CurrentUser;
import cn.workorders.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AccountService {
  private final JdbcTemplate db;
  private final PasswordEncoder passwords;

  public AccountService(JdbcTemplate db, PasswordEncoder passwords) {
    this.db = db;
    this.passwords = passwords;
  }

  static String role(String v) {
    return Inputs.choice("role", v, "ADMIN", "SUPERVISOR", "TECHNICIAN");
  }

  static String status(String v) {
    return Inputs.choice("status", v, "ACTIVE", "DISABLED");
  }

  private static Map<String, Object> view(ResultSet r, int row) throws SQLException {
    return Map.of(
        "id",
        Long.toString(r.getLong("id")),
        "username",
        r.getString("username"),
        "displayName",
        r.getString("display_name"),
        "role",
        r.getString("role"),
        "status",
        r.getString("status"),
        "mustChangePassword",
        r.getBoolean("must_change_password"),
        "version",
        r.getInt("version"),
        "createdAt",
        DbPage.utc(r, "created_at"),
        "updatedAt",
        DbPage.utc(r, "updated_at"));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(
      String username, String display, String role, String status, int page, int size) {
    CurrentUser.require("ADMIN");
    List<String> where = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    if (username != null && !Inputs.trim(username).isEmpty()) {
      where.add("username LIKE ? ESCAPE '!'");
      args.add(Inputs.like(Inputs.trim(username).toLowerCase(Locale.ROOT)));
    }
    if (display != null && !Inputs.trim(display).isEmpty()) {
      where.add("display_name LIKE ? ESCAPE '!'");
      args.add(Inputs.like(Inputs.trim(display)));
    }
    if (role != null) {
      where.add("role=?");
      args.add(role(role));
    }
    if (status != null) {
      where.add("status=?");
      args.add(status(status));
    }
    return DbPage.read(db, "sys_user", where, args, AccountService::view, page, size);
  }

  private Map<String, Object> one(long id, boolean lock) {
    return db
        .query(
            "SELECT * FROM sys_user WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            AccountService::view,
            id)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  @Transactional
  public Map<String, Object> create(JsonNode body) {
    CurrentUser.require("ADMIN");
    Inputs.fields(body, "username", "displayName", "role", "password");
    String login = Inputs.username(Inputs.string(body, "username", true, 4, 32));
    String name = Inputs.string(body, "displayName", true, 1, 40);
    String role = role(Inputs.string(body, "role", false, 1, 16));
    String hash = passwords.encode(Inputs.string(body, "password", false, 8, 64));
    var key = new GeneratedKeyHolder();
    try {
      db.update(
          c -> {
            var p =
                c.prepareStatement(
                    "INSERT INTO sys_user(username,display_name,role,password_hash)"
                        + " VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            p.setString(1, login);
            p.setString(2, name);
            p.setString(3, role);
            p.setString(4, hash);
            return p;
          },
          key);
    } catch (DuplicateKeyException ex) {
      throw new ApiException(409, "USERNAME_EXISTS", "用户名已存在");
    }
    return one(Objects.requireNonNull(key.getKey()).longValue(), false);
  }

  private Map<String, Object> locked(long id, int version) {
    var current = one(id, true);
    if (!current.get("version").equals(version)) throw ApiException.conflict();
    return current;
  }

  @Transactional
  public Map<String, Object> rename(long id, JsonNode body) {
    CurrentUser.require("ADMIN");
    Inputs.fields(body, "version", "displayName");
    var current = locked(id, Inputs.version(body));
    String name = Inputs.string(body, "displayName", true, 1, 40);
    if (name.equals(current.get("displayName"))) return current;
    db.update(
        "UPDATE sys_user SET display_name=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE"
            + " id=?",
        name,
        id);
    return one(id, false);
  }

  @Transactional
  public Map<String, Object> setStatus(long id, JsonNode body) {
    var admin = CurrentUser.require("ADMIN");
    Inputs.fields(body, "version", "status");
    var current = locked(id, Inputs.version(body));
    String status = status(Inputs.string(body, "status", false, 1, 8));
    if (admin.id() == id && status.equals("DISABLED"))
      throw new ApiException(409, "SELF_DISABLE_FORBIDDEN", "不能停用自己的账号");
    if (status.equals(current.get("status"))) return current;
    db.update(
        "UPDATE sys_user SET"
            + " status=?,auth_revision=auth_revision+?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
            + " WHERE id=?",
        status,
        status.equals("DISABLED") ? 1 : 0,
        id);
    return one(id, false);
  }

  @Transactional
  public Map<String, Object> reset(long id, JsonNode body) {
    CurrentUser.require("ADMIN");
    Inputs.fields(body, "version", "newPassword");
    locked(id, Inputs.version(body));
    String next = Inputs.string(body, "newPassword", false, 8, 64);
    String old =
        db.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, id);
    if (passwords.matches(next, old)) throw ApiException.invalid("newPassword", "新密码不能与原密码相同");
    db.update(
        "UPDATE sys_user SET"
            + " password_hash=?,auth_revision=auth_revision+1,must_change_password=true,version=version+1,updated_at=UTC_TIMESTAMP(3)"
            + " WHERE id=?",
        passwords.encode(next),
        id);
    return one(id, false);
  }
}
