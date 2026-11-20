package cn.workorders.auth;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "DEMO_DB_URL")
@EnableConfigurationProperties(DataSourceProperties.class)
class DemoConfiguration {
  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource businessPool(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean
  HikariDataSource demoPool(Environment env) {
    var ds = new HikariDataSource();
    ds.setJdbcUrl(env.getRequiredProperty("DEMO_DB_URL"));
    ds.setUsername(env.getRequiredProperty("DEMO_DB_USER"));
    ds.setPassword(env.getRequiredProperty("DEMO_DB_PASSWORD"));
    ds.setPoolName("workorders-demo");
    ds.setMaximumPoolSize(6);
    ds.setConnectionInitSql("SET time_zone = '+00:00'");
    return ds;
  }

  @Bean
  @Primary
  DataSource dataSource(
      @Qualifier("businessPool") DataSource business,
      @Qualifier("demoPool") DataSource demo,
      PasswordEncoder encoder)
      throws SQLException {
    try (var main = business.getConnection();
        var test = demo.getConnection()) {
      if (!"workorders_demo".equals(test.getCatalog())
          || "workorders_demo".equalsIgnoreCase(main.getCatalog())
          || main.getMetaData().getUserName().equalsIgnoreCase(test.getMetaData().getUserName())) {
        throw new IllegalStateException("测试登录需要独立的 workorders_demo 库和数据库账号");
      }
      var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
      schema.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
      schema.populate(test);
      initializeAccounts(test, encoder);
    }
    return new DemoRouting(business, demo);
  }

  private static void initializeAccounts(Connection db, PasswordEncoder encoder)
      throws SQLException {
    var random = new SecureRandom();
    boolean previous = db.getAutoCommit();
    db.setAutoCommit(false);
    try (var exists = db.prepareStatement("SELECT 1 FROM sys_user WHERE username=?");
        var insert =
            db.prepareStatement(
                "INSERT INTO"
                    + " sys_user(username,display_name,role,password_hash,must_change_password)"
                    + " VALUES(?,?,?,?,false) ON DUPLICATE KEY UPDATE id=id")) {
      boolean fresh;
      try (var state = db.createStatement();
          var rows =
              state.executeQuery(
                  "SELECT NOT EXISTS(SELECT 1 FROM sys_user) AND NOT EXISTS(SELECT 1 FROM"
                      + " equipment)")) {
        rows.next();
        fresh = rows.getBoolean(1);
      }
      for (var role : DemoRole.values()) {
        exists.setString(1, role.username);
        try (var found = exists.executeQuery()) {
          if (found.next()) continue;
        }
        byte[] password = new byte[32];
        random.nextBytes(password);
        insert.setString(1, role.username);
        insert.setString(2, role.displayName);
        insert.setString(3, role.name());
        insert.setString(4, encoder.encode(Base64.getEncoder().encodeToString(password)));
        insert.executeUpdate();
      }
      if (fresh) {
        try (var device = db.createStatement()) {
          device.executeUpdate(
              "INSERT INTO equipment(code,name) VALUES('P-02','二号循环泵') ON DUPLICATE KEY UPDATE"
                  + " id=id");
        }
      }
      db.commit();
    } catch (SQLException | RuntimeException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(previous);
    }
  }
}
