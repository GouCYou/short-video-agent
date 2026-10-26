package com.shortvideo.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
class AuthFilter extends OncePerRequestFilter {
  private final AuthService auth;
  private final ObjectMapper mapper;

  AuthFilter(AuthService auth, ObjectMapper mapper) {
    this.auth = auth;
    this.mapper = mapper;
  }

  static String token(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Cache-Control", "no-store");
    String path = request.getRequestURI();
    if (!path.startsWith("/api/")
        || (request.getMethod().equals("POST")
            && (path.equals("/api/auth/login") || path.equals("/api/auth/register")))
        || (request.getMethod().equals("GET") && path.equals("/api/health"))) {
      chain.doFilter(request, response);
      return;
    }
    try {
      AuthService.Account account = auth.authenticate(token(request));
      if (account == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录或重新登录");
      if (path.startsWith("/api/admin/") && !account.role().equals("ADMIN"))
        throw new ApiException(HttpStatus.FORBIDDEN, "需要管理员权限");
      request.setAttribute("account", account);
      chain.doFilter(request, response);
    } catch (ApiException ex) {
      response.setStatus(ex.status.value());
      if (ex.retryAfterSeconds != null)
        response.setHeader("Retry-After", Long.toString(ex.retryAfterSeconds));
      response.setContentType("application/json;charset=UTF-8");
      mapper.writeValue(response.getOutputStream(), Map.of("message", ex.getMessage()));
    }
  }
}
