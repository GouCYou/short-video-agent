package com.shortvideo.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;

@Service
class AuthService implements ApplicationRunner {
  record Account(
      String id,
      String username,
      String nickname,
      String role,
      boolean enabled,
      String createdAt,
      String hash,
      int sessionVersion) {
    Map<String, Object> publicView() {
      return Map.of(
          "id",
          id,
          "username",
          username,
          "nickname",
          nickname,
          "role",
          role,
          "enabled",
          enabled,
          "createdAt",
          createdAt);
    }
  }

  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final TransactionTemplate tx;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final SecureRandom random = new SecureRandom();
  private final String seedUsername, seedPassword;
  private final Duration sessionTtl;
  private final long registrationMaxAttempts;
  private final Duration registrationWindow;

  AuthService(
      JdbcTemplate db,
      StringRedisTemplate redis,
      TransactionTemplate tx,
      @Value("${app.admin-username}") String seedUsername,
      @Value("${app.admin-password}") String seedPassword,
      @Value("${app.session-days:7}") long sessionDays,
      @Value("${app.registration-max-attempts:10}") long registrationMaxAttempts,
      @Value("${app.registration-window-seconds:3600}") long registrationWindowSeconds) {
    this.db = db;
    this.redis = redis;
    this.tx = tx;
    this.seedUsername = seedUsername;
    this.seedPassword = seedPassword;
    this.sessionTtl = Duration.ofDays(sessionDays);
    if (registrationMaxAttempts < 1
        || registrationMaxAttempts > 10000
        || registrationWindowSeconds < 1
        || registrationWindowSeconds > 604800)
      throw new IllegalStateException(
          "Registration limits must be 1..10000 attempts and 1..604800 seconds");
    this.registrationMaxAttempts = registrationMaxAttempts;
    this.registrationWindow = Duration.ofSeconds(registrationWindowSeconds);
  }

  Account find(String id) {
    return db
        .query(
            "SELECT * FROM users WHERE id=?",
            (rs, n) ->
                new Account(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("nickname"),
                    rs.getString("role"),
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("created_at").toInstant().toString(),
                    rs.getString("password_hash"),
                    rs.getInt("session_version")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  Account byUsername(String name) {
    return db
        .query("SELECT id FROM users WHERE username=?", (rs, n) -> rs.getString(1), name)
        .stream()
        .findFirst()
        .map(this::find)
        .orElse(null);
  }

  Account current() {
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    return (Account) attributes.getRequest().getAttribute("account");
  }

  void validatePassword(String password) {
    if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiException.bad("密码至少 8 个字符，且 UTF-8 长度不能超过 72 字节");
  }

  Map<String, Object> register(Map<String, Object> body, HttpServletRequest request) {
    String username = Json.required(body, "username", 40).toLowerCase(Locale.ROOT);
    if (!username.matches("[a-z0-9_]{3,40}")) throw ApiException.bad("用户名须为 3 到 40 位字母、数字或下划线");
    String password = Json.str(body, "password", "");
    validatePassword(password);
    String nickname = Json.optional(body, "nickname", username, 80);
    rateLimit(
        "register:" + request.getRemoteAddr(),
        registrationMaxAttempts,
        registrationWindow,
        "注册次数过多");
    String id = Json.id();
    try {
      db.update(
          "INSERT INTO users(id,username,password_hash,nickname,role,enabled)"
              + " VALUES(?,?,?,?,'USER',TRUE)",
          id,
          username,
          passwords.encode(password),
          nickname.isBlank() ? username : nickname);
    } catch (DuplicateKeyException ex) {
      throw ApiException.conflict("用户名已存在");
    }
    return issue(find(id));
  }

  Map<String, Object> login(Map<String, Object> body, HttpServletRequest request) {
    String username = Json.required(body, "username", 40).toLowerCase(Locale.ROOT);
    rateLimit("login:" + request.getRemoteAddr() + ":" + username, 20, Duration.ofMinutes(15));
    String password = Json.str(body, "password", "");
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw ApiException.bad("用户名或密码错误");
    Account a = byUsername(username);
    String hash =
        a == null ? "$2a$12$YMOmhuvRNZ6gZeJhiERvPO5T6F.WvNvI4tWcFVUk5n92N1bQUqU0i" : a.hash();
    boolean valid = passwords.matches(password, hash);
    if (a == null || !valid) throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    if (!a.enabled()) throw new ApiException(HttpStatus.FORBIDDEN, "该账号已被停用");
    return issue(a);
  }

  private Map<String, Object> issue(Account a) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    try {
      redis.opsForValue().set(sessionKey(token), a.id() + ":" + a.sessionVersion(), sessionTtl);
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 会话服务不可用");
    }
    return Map.of("token", token, "user", a.publicView());
  }

  Account authenticate(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
    try {
      String stored = redis.opsForValue().get(sessionKey(token));
      if (stored == null) return null;
      String[] parts = stored.split(":", 2);
      Account a = find(parts[0]);
      if (a == null
          || !a.enabled()
          || parts.length < 2
          || !parts[1].equals(Integer.toString(a.sessionVersion()))) return null;
      return a;
    } catch (org.springframework.dao.DataAccessException ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂时不可用");
    }
  }

  void logout(String token) {
    if (token != null) redis.delete(sessionKey(token));
  }

  Map<String, Object> updateMe(Map<String, Object> body) {
    String nickname = Json.required(body, "nickname", 80);
    db.update("UPDATE users SET nickname=? WHERE id=?", nickname, current().id());
    return find(current().id()).publicView();
  }

  void changePassword(Map<String, Object> body) {
    Account a = current();
    if (!passwords.matches(Json.str(body, "oldPassword", ""), a.hash()))
      throw ApiException.bad("原密码错误");
    String password = Json.str(body, "newPassword", "");
    validatePassword(password);
    db.update(
        "UPDATE users SET password_hash=?,session_version=session_version+1 WHERE id=?",
        passwords.encode(password),
        a.id());
  }

  List<Map<String, Object>> users() {
    return db.query(
        "SELECT id FROM users ORDER BY created_at DESC",
        (rs, n) -> find(rs.getString(1)).publicView());
  }

  Map<String, Object> updateUser(String id, Map<String, Object> body) {
    return tx.execute(
        status -> {
          Account a = find(id);
          if (a == null) throw ApiException.missing();
          boolean enabled = Json.bool(body, "enabled", a.enabled());
          String role = Json.optional(body, "role", a.role(), 10);
          if (!Set.of("USER", "ADMIN").contains(role)) throw ApiException.bad("无效角色");
          if (id.equals(current().id()) && (!enabled || !role.equals("ADMIN")))
            throw ApiException.conflict("不能停用或降低当前管理员的权限");
          db.update(
              "UPDATE users SET enabled=?,role=?,session_version=session_version+1 WHERE id=?",
              enabled,
              role,
              id);
          return find(id).publicView();
        });
  }

  void rateLimit(String suffix, long max, Duration duration) {
    rateLimit(suffix, max, duration, "请求过于频繁");
  }

  void rateLimit(String suffix, long max, Duration duration, String reason) {
    try {
      String key = "sva:rate:" + hash(suffix);
      Long count = redis.opsForValue().increment(key);
      Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
      if (count != null && (count == 1 || ttl == null || ttl < 0)) {
        redis.expire(key, duration);
        ttl = duration.toSeconds();
      }
      if (count != null && count > max) {
        long retryAfter = Math.max(1, ttl == null ? duration.toSeconds() : ttl);
        throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS, reason + "，请在 " + retryAfter + " 秒后重试", retryAfter);
      }
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 服务暂时不可用");
    }
  }

  static String sessionKey(String token) {
    return "sva:session:" + hash(token);
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Override
  public void run(ApplicationArguments args) {
    if (seedUsername.isBlank() && seedPassword.isBlank()) return;
    if (seedUsername.isBlank() || seedPassword.isBlank())
      throw new IllegalStateException(
          "Both SEED_ADMIN_USERNAME and SEED_ADMIN_PASSWORD are required");
    String name = seedUsername.toLowerCase(Locale.ROOT);
    if (!name.matches("[a-z0-9_]{3,40}"))
      throw new IllegalStateException("Invalid seed admin username");
    if (seedPassword.length() < 6 || seedPassword.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalStateException("Seed admin password must be 6..72 UTF-8 bytes");
    if (byUsername(name) != null) return;
    db.update(
        "INSERT INTO users(id,username,password_hash,nickname,role,enabled)"
            + " VALUES(?,?,?,?,'ADMIN',TRUE)",
        Json.id(),
        name,
        passwords.encode(seedPassword),
        "管理员");
  }
}
