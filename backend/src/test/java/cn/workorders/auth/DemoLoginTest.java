package cn.workorders.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.workorders.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

class DemoLoginTest {
  private final ObjectMapper json = new ObjectMapper();
  private final DemoAccess access =
      new DemoAccess(
          new MockEnvironment()
              .withProperty("DEMO_DB_URL", "configured")
              .withProperty("server.address", "127.0.0.1"));

  @AfterEach
  void clean() {
    DemoRouting.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void optionsRequireConfiguredLocalRequest() {
    var req = new MockHttpServletRequest();
    assertTrue(access.enabled(req));
    req.setRemoteAddr("192.0.2.1");
    assertFalse(access.enabled(req));
    req.setRemoteAddr("127.0.0.1");
    req.addHeader("X-Forwarded-For", "192.0.2.1");
    assertFalse(access.enabled(req));
    assertFalse(new DemoAccess(new MockEnvironment()).enabled(new MockHttpServletRequest()));
  }

  @Test
  void testSessionRefreshesIdentityInDemoAndReleasesThreadAfterFailure() throws Exception {
    var main = mock(DataSource.class);
    var test = mock(DataSource.class);
    var mainConnection = mock(Connection.class);
    var testConnection = mock(Connection.class);
    when(main.getConnection()).thenReturn(mainConnection);
    when(test.getConnection()).thenReturn(testConnection);
    var router = new DemoRouting(main, test);
    router.afterPropertiesSet();

    var store = mock(AuthStore.class);
    when(store.active(1))
        .thenAnswer(
            inv -> {
              assertSame(testConnection, assertDoesNotThrow(() -> router.getConnection()));
              return Optional.of(new CurrentUser(1, "demo_admin", "管理员测试", "ADMIN", false, 1));
            });
    var filter = new SessionSecurity.IdentityFilter(store, json, access);
    var req = new MockHttpServletRequest();
    req.setServletPath("/api/users");
    req.getSession()
        .setAttribute(SessionSecurity.IDENTITY, new SessionSecurity.SessionIdentity(1, 1, true));
    assertThrows(
        IllegalStateException.class,
        () ->
            filter.doFilter(
                req,
                new MockHttpServletResponse(),
                (r, s) -> {
                  assertSame(testConnection, assertDoesNotThrow(() -> router.getConnection()));
                  throw new IllegalStateException("simulated request error");
                }));
    assertSame(mainConnection, router.getConnection());
  }

  @Test
  void onlyPredefinedRoleIsAcceptedAndFailedTestLoginPreservesCurrentSession() throws Exception {
    var store = mock(AuthStore.class);
    var controller = new AuthController(store, new HttpSessionCsrfTokenRepository(), access);
    var req = new MockHttpServletRequest();
    var original = new SessionSecurity.SessionIdentity(1, 2, false);
    req.getSession().setAttribute(SessionSecurity.IDENTITY, original);
    assertThrows(
        ApiException.class,
        () ->
            controller.testLogin(
                json.readTree("{\"role\":\"ADMIN\",\"username\":\"admin\"}"),
                req,
                new MockHttpServletResponse()));
    assertThrows(
        ApiException.class,
        () ->
            controller.testLogin(
                json.readTree("{\"role\":\"OTHER\"}"), req, new MockHttpServletResponse()));
    when(store.testAccount(DemoRole.ADMIN))
        .thenThrow(new ApiException(403, "TEST_ACCOUNT_UNAVAILABLE", "停用"));
    assertThrows(
        ApiException.class,
        () ->
            controller.testLogin(
                json.readTree("{\"role\":\"ADMIN\"}"), req, new MockHttpServletResponse()));
    assertSame(original, req.getSession().getAttribute(SessionSecurity.IDENTITY));
  }
}
