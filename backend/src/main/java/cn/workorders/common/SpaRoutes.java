package cn.workorders.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaRoutes {
  @GetMapping({
    "/login",
    "/change-password",
    "/connection",
    "/forbidden",
    "/admin/users",
    "/equipment",
    "/orders",
    "/orders/new",
    "/orders/{id:[0-9]+}",
    "/orders/{id:[0-9]+}/edit"
  })
  public String index() {
    return "forward:/index.html";
  }
}
