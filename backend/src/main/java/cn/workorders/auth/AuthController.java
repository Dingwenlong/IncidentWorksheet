package cn.workorders.auth;

import cn.workorders.common.Inputs;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthStore store;
  private final HttpSessionCsrfTokenRepository csrf;
  private final DemoAccess demo;

  public AuthController(AuthStore store, HttpSessionCsrfTokenRepository csrf, DemoAccess demo) {
    this.store = store;
    this.csrf = csrf;
    this.demo = demo;
  }

  @GetMapping("/csrf")
  public Map<String, String> token(CsrfToken token) {
    return Map.of("token", token.getToken());
  }

  @PostMapping("/login")
  public Map<String, Object> login(
      @RequestBody JsonNode body, HttpServletRequest req, HttpServletResponse res) {
    Inputs.fields(body, "username", "password");
    DemoRouting.select(false);
    var u =
        store.login(
            Inputs.username(Inputs.string(body, "username", true, 4, 32)),
            Inputs.string(body, "password", false, 6, 64));
    return startSession(u, false, req, res);
  }

  @GetMapping("/test-options")
  public Map<String, Boolean> testOptions(HttpServletRequest req) {
    return Map.of("enabled", demo.enabled(req));
  }

  @PostMapping("/test-login")
  public Map<String, Object> testLogin(
      @RequestBody JsonNode body, HttpServletRequest req, HttpServletResponse res) {
    if (!demo.enabled(req))
      throw new cn.workorders.common.ApiException(403, "TEST_LOGIN_DISABLED", "测试登录未启用");
    Inputs.fields(body, "role");
    var role = DemoRole.from(Inputs.string(body, "role", false, 1, 16));
    DemoRouting.select(true);
    return startSession(store.testAccount(role), true, req, res);
  }

  private Map<String, Object> startSession(
      CurrentUser u, boolean test, HttpServletRequest req, HttpServletResponse res) {
    var session = req.getSession();
    req.changeSessionId();
    session.setAttribute(
        SessionSecurity.IDENTITY, new SessionSecurity.SessionIdentity(u.id(), u.revision(), test));
    csrf.saveToken(null, req, res);
    return u.me();
  }

  @GetMapping("/me")
  public Map<String, Object> me() {
    return CurrentUser.require().me();
  }

  @PutMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(
      @RequestBody JsonNode body, HttpServletRequest req, HttpServletResponse res) {
    Inputs.fields(body, "currentPassword", "newPassword");
    store.change(
        CurrentUser.require(),
        Inputs.string(body, "currentPassword", false, 6, 64),
        Inputs.string(body, "newPassword", false, 8, 64));
    invalidate(req, res);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest req, HttpServletResponse res) {
    invalidate(req, res);
  }

  private void invalidate(HttpServletRequest req, HttpServletResponse res) {
    req.getSession(false).invalidate();
    var cookie = new Cookie("JSESSIONID", "");
    cookie.setMaxAge(0);
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    cookie.setSecure(req.isSecure());
    res.addCookie(cookie);
  }
}
