package cn.smartserve.api;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(
      Map.of(
        "status",
        e.getStatusCode().value(),
        "message",
        e.getReason() == null ? "请求未完成" : e.getReason()
      )
    );
  }

  @ExceptionHandler(
    {
      MethodArgumentNotValidException.class,
      org.springframework.http.converter.HttpMessageNotReadableException.class,
    }
  )
  ResponseEntity<?> validation(Exception e) {
    return ResponseEntity.badRequest().body(
      Map.of("status", 400, "message", "请检查必填项、确认选项和输入长度")
    );
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    return ResponseEntity.internalServerError().body(
      Map.of(
        "status",
        500,
        "message",
        "服务暂时无法完成，请重试；数据与密钥不会出现在错误信息中"
      )
    );
  }
}
