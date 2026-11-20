package cn.workorders.common;

import java.util.Map;

public class ApiException extends RuntimeException {
  private final int status;
  private final String code;
  private final Map<String, String> errors;

  public ApiException(int status, String code, String message) {
    this(status, code, message, Map.of());
  }

  private ApiException(int status, String code, String message, Map<String, String> errors) {
    super(message);
    this.status = status;
    this.code = code;
    this.errors = errors;
  }

  public static ApiException invalid(String field, String message) {
    return new ApiException(422, "VALIDATION_FAILED", "请检查输入字段", Map.of(field, message));
  }

  public static ApiException notFound() {
    return new ApiException(404, "NOT_FOUND", "记录不存在");
  }

  public static ApiException conflict() {
    return new ApiException(409, "VERSION_CONFLICT", "记录已更新，请重新加载");
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public Map<String, Object> body() {
    if (errors.isEmpty()) return Map.of("code", code, "message", getMessage());
    return Map.of("code", code, "message", getMessage(), "fieldErrors", errors);
  }
}
