package cn.workorders.orders;

import cn.workorders.auth.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private final OrderWriteService writes;
  private final OrderReadService reads;

  OrderController(OrderWriteService writes, OrderReadService reads) {
    this.writes = writes;
    this.reads = reads;
  }

  @PostMapping
  public ResponseEntity<OrderRow> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) JsonNode body) {
    CurrentUser actor = CurrentUser.require("TECHNICIAN");
    OrderWriteService.Created result = writes.create(actor, OrderInput.create(body, key));
    return ResponseEntity.status(result.first() ? 201 : 200).body(result.order());
  }

  @PatchMapping("/{id}")
  public OrderRow edit(
      @PathVariable("id") String id, @RequestBody(required = false) JsonNode body) {
    CurrentUser actor = CurrentUser.require("TECHNICIAN");
    return writes.change(OrderInput.id(id, "id"), actor, OrderInput.edit(body));
  }

  @PostMapping("/{id}/transitions")
  public OrderRow transition(
      @PathVariable("id") String id, @RequestBody(required = false) JsonNode body) {
    CurrentUser actor = CurrentUser.require("TECHNICIAN");
    return writes.change(OrderInput.id(id, "id"), actor, OrderInput.transition(body));
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam MultiValueMap<String, String> query) {
    CurrentUser.require("TECHNICIAN", "SUPERVISOR");
    return reads.list(OrderFilter.parse(query));
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable("id") String id) {
    CurrentUser actor = CurrentUser.require("TECHNICIAN", "SUPERVISOR");
    return reads.detail(OrderInput.id(id, "id"), actor);
  }
}
