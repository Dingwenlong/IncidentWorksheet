package cn.workorders.accounts;

import cn.workorders.common.Inputs;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
  private final DataSource ds;
  private final Environment env;
  private final PasswordEncoder passwords;

  public BootstrapAdmin(DataSource ds, Environment env, PasswordEncoder passwords) {
    this.ds = ds;
    this.env = env;
    this.passwords = passwords;
  }

  @Override
  public void run(ApplicationArguments ignored) throws SQLException {
    try (var c = ds.getConnection();
        var statement = c.createStatement()) {
      String lock;
      try (var rows =
          statement.executeQuery("SELECT SHA2(CONCAT('workorder-bootstrap:',DATABASE()),256)")) {
        rows.next();
        lock = rows.getString(1);
      }
      try (var get = c.prepareStatement("SELECT GET_LOCK(?,30)")) {
        get.setString(1, lock);
        try (var r = get.executeQuery()) {
          if (!r.next() || r.getInt(1) != 1) throw new SQLException("启动初始化锁超时");
        }
      }
      try {
        try (var r = statement.executeQuery("SELECT EXISTS(SELECT 1 FROM sys_user)")) {
          r.next();
          if (r.getBoolean(1)) return;
        }
        String name = env.getRequiredProperty("BOOTSTRAP_ADMIN_USERNAME");
        String pass = env.getRequiredProperty("BOOTSTRAP_ADMIN_PASSWORD");
        int count = pass.codePointCount(0, pass.length());
        if (count < 8 || count > 64) throw new IllegalStateException("初始管理员密码须为8至64字符");
        try (var insert =
            c.prepareStatement(
                "INSERT INTO sys_user(username,display_name,role,password_hash)"
                    + " VALUES(?,'系统管理员','ADMIN',?)")) {
          insert.setString(1, Inputs.username(name));
          insert.setString(2, passwords.encode(pass));
          insert.executeUpdate();
        }
      } finally {
        try (var release = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
          release.setString(1, lock);
          release.execute();
        }
      }
    }
  }
}
