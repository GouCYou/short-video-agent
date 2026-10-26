package com.shortvideo.agent;

import org.springframework.http.HttpStatus;

class ApiException extends RuntimeException {
  final HttpStatus status;
  final Long retryAfterSeconds;

  ApiException(HttpStatus status, String message) {
    this(status, message, null);
  }

  ApiException(HttpStatus status, String message, Long retryAfterSeconds) {
    super(message);
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "记录不存在或无权访问");
  }
}
