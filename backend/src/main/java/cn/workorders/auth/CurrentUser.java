package cn.workorders.auth;

import cn.workorders.common.ApiException;
import java.io.Serializable;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;

public record CurrentUser(
    long id,
    String username,
    String displayName,
    String role,
    boolean mustChangePassword,
    long revision)
    implements Serializable {
  public static CurrentUser require(String... roles) {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof CurrentUser user))
      throw new ApiException(401, "AUTH_REQUIRED", "请先登录");
    if (roles.length > 0 && !Arrays.asList(roles).contains(user.role()))
      throw new ApiException(403, "FORBIDDEN", "当前账号无此权限");
    return user;
  }

  public Map<String, Object> me() {
    return Map.of(
        "id",
        String.valueOf(id),
        "username",
        username,
        "displayName",
        displayName,
        "role",
        role,
        "mustChangePassword",
        mustChangePassword);
  }
}
