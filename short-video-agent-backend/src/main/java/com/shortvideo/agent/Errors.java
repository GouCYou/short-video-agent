package com.shortvideo.agent;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class Errors {
  private static final Logger log = LoggerFactory.getLogger(Errors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException ex) {
    var response = ResponseEntity.status(ex.status);
    if (ex.retryAfterSeconds != null)
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds));
    return response.body(Map.of("message", ex.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> upload(Exception ex) {
    return ResponseEntity.status(413).body(Map.of("message", "文件大小不能超过 20MB"));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> input(Exception ex) {
    return ResponseEntity.badRequest().body(Map.of("message", "请求参数格式无效"));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception ex) {
    return ResponseEntity.status(404).body(Map.of("message", "接口不存在"));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(Exception ex) {
    return ResponseEntity.status(405).body(Map.of("message", "不支持该请求方法"));
  }

  @ExceptionHandler(org.springframework.data.redis.RedisConnectionFailureException.class)
  ResponseEntity<?> redis(Exception ex) {
    return ResponseEntity.status(503).body(Map.of("message", "Redis 服务暂时不可用"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(DataAccessException ex) {
    log.error("Database operation failed: {}", ex.getClass().getSimpleName());
    return ResponseEntity.status(503).body(Map.of("message", "数据库暂时不可用，请稍后重试"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception ex) {
    log.error("Unhandled {}", ex.getClass().getSimpleName());
    return ResponseEntity.status(500).body(Map.of("message", "服务暂时无法处理该请求"));
  }
}
