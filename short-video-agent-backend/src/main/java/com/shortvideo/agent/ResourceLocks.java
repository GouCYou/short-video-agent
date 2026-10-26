package com.shortvideo.agent;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class ResourceLocks {
  private final StringRedisTemplate redis;
  private static final DefaultRedisScript<Long> RELEASE =
      new DefaultRedisScript<>(
          "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else"
              + " return 0 end",
          Long.class);
  private static final DefaultRedisScript<Long> RENEW =
      new DefaultRedisScript<>(
          "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('expire',KEYS[1],900)"
              + " else return 0 end",
          Long.class);

  ResourceLocks(StringRedisTemplate redis) {
    this.redis = redis;
  }

  boolean acquire(String resource, String owner) {
    try {
      return Boolean.TRUE.equals(
          redis.opsForValue().setIfAbsent("sva:lock:" + resource, owner, Duration.ofMinutes(15)));
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 执行锁服务不可用");
    }
  }

  void release(String resource, String owner) {
    redis.execute(RELEASE, List.of("sva:lock:" + resource), owner);
  }

  boolean renew(String resource, String owner) {
    return Long.valueOf(1).equals(redis.execute(RENEW, List.of("sva:lock:" + resource), owner));
  }

  <T> T with(String resource, Supplier<T> action) {
    String owner = Json.id();
    if (!acquire(resource, owner)) throw ApiException.conflict("该资料正在处理中，请稍后重试");
    try {
      return action.get();
    } finally {
      try {
        release(resource, owner);
      } catch (Exception ignored) {
      }
    }
  }
}
