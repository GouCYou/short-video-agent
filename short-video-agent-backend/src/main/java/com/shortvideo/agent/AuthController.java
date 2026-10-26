package com.shortvideo.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class AuthController {
  private final AuthService auth;

  AuthController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @PostMapping("/auth/register")
  Map<String, Object> register(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    return auth.register(body, r);
  }

  @PostMapping("/auth/login")
  Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    return auth.login(body, r);
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest r) {
    auth.logout(AuthFilter.token(r));
  }

  @GetMapping("/auth/me")
  Map<String, Object> me() {
    return auth.current().publicView();
  }

  @PutMapping("/auth/me")
  Map<String, Object> me(@RequestBody Map<String, Object> body) {
    return auth.updateMe(body);
  }

  @PutMapping("/auth/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void password(@RequestBody Map<String, Object> body) {
    auth.changePassword(body);
  }

  @GetMapping("/admin/users")
  List<Map<String, Object>> users() {
    return auth.users();
  }

  @PutMapping("/admin/users/{id}")
  Map<String, Object> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return auth.updateUser(id, body);
  }
}
