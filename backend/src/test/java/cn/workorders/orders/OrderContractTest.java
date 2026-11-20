package cn.workorders.orders;

import static org.junit.jupiter.api.Assertions.*;

import cn.workorders.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class OrderContractTest {
  private final ObjectMapper json = new ObjectMapper();
  private static final String REQUEST_KEY = "d6afe9f9-41ef-47d5-84c6-ff796f42e7f1";

  @Test
  void canonicalHashUsesTheEffectiveInputAndPreservesOriginalText() throws Exception {
    var body =
        json.createObjectNode()
            .put("equipmentId", "18")
            .put("title", "  二号泵  ")
            .put("faultText", "🔧 E17！\r\n\u001a恢复。")
            .put("actionText", "\u00a0");
    var order = OrderInput.create(body, REQUEST_KEY);
    String expected = "[\"18\",\"二号泵\",\"🔧 E17！\\r\\n\\u001a恢复。\",\"\"]";
    assertArrayEquals(
        MessageDigest.getInstance("SHA-256").digest(expected.getBytes(StandardCharsets.UTF_8)),
        order.hash());
    assertEquals("🔧 E17！\r\n\u001a恢复。", order.faultText());
    body.remove("actionText");
    assertArrayEquals(order.hash(), OrderInput.create(body, REQUEST_KEY).hash());
  }

  @Test
  void inputTypesCannotSilentlyClearTheOriginalOrOverrideTheOwner() throws Exception {
    assertThrows(
        ApiException.class,
        () -> OrderInput.edit(json.readTree("{\"version\":1,\"actionText\":null}")));
    assertThrows(
        ApiException.class,
        () -> OrderInput.edit(json.readTree("{\"version\":1,\"creatorId\":\"42\"}")));
    assertThrows(
        ApiException.class,
        () ->
            OrderInput.transition(
                json.readTree("{\"version\":1,\"action\":\"START\",\"title\":\"x\"}")));
    assertEquals(
        "", OrderInput.edit(json.readTree("{\"version\":1,\"actionText\":\"\"}")).actionText());
    assertNull(OrderInput.edit(json.readTree("{\"version\":1,\"title\":\"x\"}")).actionText());
  }

  @Test
  void filtersUseLiteralSubstringAndShanghaiCalendarDays() {
    var query = new LinkedMultiValueMap<String, String>();
    query.add("keyword", "!%_");
    query.add("dateFrom", "2027-01-10");
    query.add("dateTo", "2027-01-10");
    var filter = OrderFilter.parse(query);
    assertEquals(
        List.of(
            "%!!!%!_%",
            "%!!!%!_%",
            "%!!!%!_%",
            "%!!!%!_%",
            "%!!!%!_%",
            "%!!!%!_%",
            LocalDateTime.of(2027, 1, 9, 16, 0),
            LocalDateTime.of(2027, 1, 10, 16, 0)),
        filter.args());
    query.add("pageSize", "101");
    assertThrows(ApiException.class, () -> OrderFilter.parse(query));
  }
}
