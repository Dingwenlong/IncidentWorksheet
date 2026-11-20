package cn.workorders.common;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiAdvice {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> failure(ApiException ex) {
    return ResponseEntity.status(ex.status()).body(ex.body());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> malformed() {
    return failure(ApiException.invalid("body", "请求必须是有效的 JSON 对象"));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> invalidValue(MethodArgumentTypeMismatchException ex) {
    return failure(ApiException.invalid(ex.getName(), "参数格式无效"));
  }
}
