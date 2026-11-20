package cn.workorders.accounts;

import cn.workorders.common.Inputs;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class AccountController {
  private final AccountService users;

  public AccountController(AccountService users) {
    this.users = users;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String displayName,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return users.list(username, displayName, role, status, page, pageSize);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(@RequestBody JsonNode body) {
    return users.create(body);
  }

  @PatchMapping("/{id}")
  public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode body) {
    return users.rename(Inputs.id(id), body);
  }

  @PutMapping("/{id}/status")
  public Map<String, Object> status(@PathVariable String id, @RequestBody JsonNode body) {
    return users.setStatus(Inputs.id(id), body);
  }

  @PostMapping("/{id}/reset-password")
  public Map<String, Object> reset(@PathVariable String id, @RequestBody JsonNode body) {
    return users.reset(Inputs.id(id), body);
  }
}
