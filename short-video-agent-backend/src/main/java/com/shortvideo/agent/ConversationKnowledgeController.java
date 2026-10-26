package com.shortvideo.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/conversations")
class ConversationKnowledgeController {
  private final KnowledgeIngestionService ingestion;
  private final String key;

  ConversationKnowledgeController(
      KnowledgeIngestionService ingestion, @Value("${app.internal-key}") String key) {
    this.ingestion = ingestion;
    this.key = key;
  }

  @PostMapping("/{run}/knowledge")
  Map<String, Object> save(
      @PathVariable String run,
      @RequestHeader(value = "X-Internal-Key", defaultValue = "") String supplied,
      @RequestBody Map<String, Object> body) {
    if (key.isBlank()
        || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "内部接口认证失败");
    return ingestion.saveFromRun(run, body);
  }
}
