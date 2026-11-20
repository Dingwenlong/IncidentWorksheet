package cn.workorders.auth;

import cn.workorders.common.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthStore {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;

  public AuthStore(JdbcTemplate db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  private static CurrentUser user(ResultSet rs) throws SQLException {
    return new CurrentUser(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("display_name"),
        rs.getString("role"),
        rs.getBoolean("must_change_password"),
        rs.getLong("auth_revision"));
  }

  public Optional<CurrentUser> active(long id) {
    return db
        .query("SELECT * FROM sys_user WHERE id=? AND status='ACTIVE'", (r, i) -> user(r), id)
        .stream()
        .findFirst();
  }

  public CurrentUser login(String username, String password) {
    var found =
        db.query(
            "SELECT * FROM sys_user WHERE username=? AND status='ACTIVE'",
            (r, i) -> new Object[] {user(r), r.getString("password_hash")},
            username);
    if (found.isEmpty() || !encoder.matches(password, (String) found.get(0)[1]))
      throw new ApiException(401, "INVALID_CREDENTIALS", "用户名或密码错误");
    return (CurrentUser) found.get(0)[0];
  }

  CurrentUser testAccount(DemoRole role) {
    return db
        .query(
            "SELECT * FROM sys_user WHERE username=? AND role=? AND status='ACTIVE'",
            (r, i) -> user(r),
            role.username,
            role.name())
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(403, "TEST_ACCOUNT_UNAVAILABLE", "该测试账号当前不可用"));
  }

  @Transactional
  public void change(CurrentUser u, String original, String next) {
    var row =
        db.queryForMap(
            "SELECT password_hash,auth_revision,status FROM sys_user WHERE id=? FOR UPDATE",
            u.id());
    if (!"ACTIVE".equals(row.get("status"))
        || ((Number) row.get("auth_revision")).longValue() != u.revision())
      throw new ApiException(401, "AUTH_REQUIRED", "请重新登录");
    if (!encoder.matches(original, (String) row.get("password_hash")))
      throw ApiException.invalid("currentPassword", "当前密码不正确");
    if (original.equals(next)) throw ApiException.invalid("newPassword", "新密码需要与原密码不同");
    db.update(
        "UPDATE sys_user SET"
            + " password_hash=?,must_change_password=false,auth_revision=auth_revision+1,version=version+1,updated_at=UTC_TIMESTAMP(3)"
            + " WHERE id=?",
        encoder.encode(next),
        u.id());
  }
}
