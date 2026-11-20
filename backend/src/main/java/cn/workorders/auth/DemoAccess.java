package cn.workorders.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class DemoAccess {
  private final boolean configured;

  DemoAccess(Environment env) {
    configured =
        env.containsProperty("DEMO_DB_URL") && loopback(env.getProperty("server.address", ""));
  }

  boolean enabled(HttpServletRequest request) {
    return configured
        && loopback(request.getRemoteAddr())
        && loopback(request.getServerName())
        && request.getHeader("Forwarded") == null
        && request.getHeader("X-Forwarded-For") == null;
  }

  private static boolean loopback(String address) {
    if (address == null) return false;
    if ("localhost".equalsIgnoreCase(address)) return true;
    if (!address.matches("[0-9a-fA-F.:\\[\\]]+")) return false;
    // Hostnames are never resolved for this access decision.
    if (!address.contains(":") && !address.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return false;
    try {
      return InetAddress.getByName(address).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
