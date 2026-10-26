package com.shortvideo.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class Json {
  private final ObjectMapper mapper;

  Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("JSON serialization failed", ex);
    }
  }

  Map<String, Object> object(String value) {
    try {
      return value == null ? null : mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception ex) {
      throw new IllegalStateException("Stored JSON invalid", ex);
    }
  }

  List<Map<String, Object>> list(String value) {
    try {
      return value == null ? List.of() : mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception ex) {
      throw new IllegalStateException("Stored JSON invalid", ex);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
  }

  static String str(Map<String, ?> data, String key, String fallback) {
    Object value = data.get(key);
    return value == null ? fallback : value.toString();
  }

  static String required(Map<String, ?> data, String key, int max) {
    String v = str(data, key, "").strip();
    if (v.isEmpty() || v.length() > max) throw ApiException.bad(key + " 不能为空且长度不能超过 " + max);
    return v;
  }

  static String optional(Map<String, ?> data, String key, String fallback, int max) {
    String v = str(data, key, fallback).strip();
    if (v.length() > max) throw ApiException.bad(key + " 长度不能超过 " + max);
    return v;
  }

  static int integer(Map<String, ?> data, String key, int fallback, int min, int max) {
    try {
      int n = data.get(key) == null ? fallback : Integer.parseInt(data.get(key).toString());
      if (n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException ex) {
      throw ApiException.bad(key + " 必须在 " + min + " 到 " + max + " 之间");
    }
  }

  static boolean bool(Map<String, ?> data, String key, boolean fallback) {
    if (!data.containsKey(key)) return fallback;
    Object v = data.get(key);
    if (v instanceof Boolean b) return b;
    throw ApiException.bad(key + " 必须为布尔值");
  }

  static String id() {
    return UUID.randomUUID().toString();
  }
}
