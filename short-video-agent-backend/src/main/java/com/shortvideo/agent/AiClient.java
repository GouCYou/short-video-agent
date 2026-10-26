package com.shortvideo.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
class AiClient {
  private final RestClient client;
  private final String key;

  AiClient(@Value("${app.ai-base-url}") String baseUrl, @Value("${app.internal-key}") String key) {
    this.key = key;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    factory.setReadTimeout(Duration.ofSeconds(360));
    client =
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("X-Internal-Key", key)
            .build();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> post(String path, Object body) {
    return (Map<String, Object>) request(HttpMethod.POST, path, body, Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> search(Object body) {
    return (List<Map<String, Object>>)
        request(HttpMethod.POST, "/internal/knowledge/search", body, List.class);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> health() {
    return (Map<String, Object>) request(HttpMethod.GET, "/internal/health", null, Map.class);
  }

  void put(String path, Object body) {
    request(HttpMethod.PUT, path, body, Map.class);
  }

  void delete(String path) {
    request(HttpMethod.DELETE, path, null, Map.class);
  }

  private Object request(HttpMethod method, String path, Object body, Class<?> resultType) {
    if (key.isBlank())
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "请配置 AI_INTERNAL_KEY 后启动 AI 服务和后端");
    try {
      var request = client.method(method).uri(path);
      if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
      return request.retrieve().body(resultType);
    } catch (RestClientResponseException ex) {
      String message = "AI 服务请求失败（" + ex.getStatusCode().value() + "）";
      try {
        var node =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(ex.getResponseBodyAsString());
        String detail = node.path("message").asText(node.path("detail").asText(""));
        if (!detail.isBlank() && detail.length() <= 500 && !detail.contains("sk-"))
          message += "：" + detail;
      } catch (Exception ignored) {
      }
      throw new ApiException(
          ex.getStatusCode().is4xxClientError() && ex.getStatusCode().value() != 401
              ? HttpStatus.BAD_REQUEST
              : HttpStatus.SERVICE_UNAVAILABLE,
          message);
    } catch (ResourceAccessException ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务不可达或响应超时，请检查服务地址、网络和模型配置");
    }
  }
}
