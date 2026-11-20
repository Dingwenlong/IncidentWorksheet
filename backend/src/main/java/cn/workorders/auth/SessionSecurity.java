package cn.workorders.auth;

import cn.workorders.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.*;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SessionSecurity {
  static final String IDENTITY = "workorders.identity";

  record SessionIdentity(long id, long revision, boolean test) implements java.io.Serializable {}

  @Bean
  PasswordEncoder passwordEncoder() {
    var pbkdf =
        new Pbkdf2PasswordEncoder("", 16, 600000, SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    return new DelegatingPasswordEncoder("pbkdf2-v1", Map.of("pbkdf2-v1", pbkdf));
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepo() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      AuthStore store,
      DemoAccess demo,
      ObjectMapper json,
      HttpSessionCsrfTokenRepository csrfRepo)
      throws Exception {
    var csrfHandler = new CsrfTokenRequestAttributeHandler();
    http.csrf(c -> c.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(csrfHandler))
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, err) -> send(json, res, 403, "INVALID_CSRF_TOKEN", "表单令牌已失效，请重新提交")))
        .addFilterBefore(new IdentityFilter(store, json, demo), CsrfFilter.class);
    return http.build();
  }

  private static void send(
      ObjectMapper json, HttpServletResponse res, int status, String code, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    json.writeValue(res.getOutputStream(), Map.of("code", code, "message", message));
  }

  static class IdentityFilter extends OncePerRequestFilter {
    private final AuthStore store;
    private final ObjectMapper json;
    private final DemoAccess demo;

    IdentityFilter(AuthStore store, ObjectMapper json, DemoAccess demo) {
      this.store = store;
      this.json = json;
      this.demo = demo;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      try {
        dispatch(req, res, chain);
      } finally {
        DemoRouting.clear();
      }
    }

    private void dispatch(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      String path = req.getServletPath();
      if (!path.startsWith("/api/")
          || path.equals("/api/auth/csrf")
          || path.equals("/api/auth/login")
          || path.equals("/api/auth/test-login")
          || path.equals("/api/auth/test-options")) {
        chain.doFilter(req, res);
        return;
      }
      var s = req.getSession(false);
      if (s == null || !(s.getAttribute(IDENTITY) instanceof SessionIdentity identity)) {
        send(json, res, 401, "AUTH_REQUIRED", "请先登录");
        return;
      }
      if (identity.test() && !demo.enabled(req)) {
        send(json, res, 403, "TEST_LOGIN_DISABLED", "测试登录未启用");
        return;
      }
      DemoRouting.select(identity.test());
      var u = store.active(identity.id()).orElse(null);
      if (u == null || u.revision() != identity.revision()) {
        s.invalidate();
        send(json, res, 401, "AUTH_REQUIRED", "登录已失效");
        return;
      }
      if (u.mustChangePassword()
          && !Set.of("/api/auth/me", "/api/auth/password", "/api/auth/logout").contains(path)) {
        send(json, res, 403, "PASSWORD_CHANGE_REQUIRED", "请先修改密码");
        return;
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              u, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.role()))));
      SecurityContextHolder.setContext(context);
      chain.doFilter(req, res);
    }
  }
}
