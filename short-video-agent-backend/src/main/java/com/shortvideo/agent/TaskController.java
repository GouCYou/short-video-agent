package com.shortvideo.agent;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class TaskController {
  private final TaskService tasks;

  TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/tasks")
  List<Map<String, Object>> list(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String status) {
    return tasks.list(search, status, false);
  }

  @PostMapping("/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return tasks.create(body, key);
  }

  @GetMapping("/tasks/{id}")
  Map<String, Object> detail(@PathVariable String id) {
    return tasks.detail(id);
  }

  @PostMapping("/tasks/{id}/select-topic")
  Map<String, Object> select(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return tasks.select(id, body);
  }

  @PostMapping("/tasks/{id}/retry")
  Map<String, Object> retry(@PathVariable String id) {
    return tasks.retry(id);
  }

  @PostMapping("/tasks/{id}/revise")
  Map<String, Object> revise(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return tasks.revise(id, body);
  }

  @PutMapping("/tasks/{id}/content")
  Map<String, Object> content(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return tasks.manual(id, body);
  }

  @PostMapping("/tasks/{id}/restore/{versionId}")
  Map<String, Object> restore(@PathVariable String id, @PathVariable String versionId) {
    return tasks.restore(id, versionId);
  }

  @DeleteMapping("/tasks/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id) {
    tasks.delete(id);
  }

  @GetMapping("/tasks/{id}/export")
  ResponseEntity<String> export(
      @PathVariable String id, @RequestParam(defaultValue = "markdown") String format) {
    if (!Set.of("markdown", "txt").contains(format))
      throw ApiException.bad("导出格式必须是 markdown 或 txt");
    String content = tasks.export(id, format.equals("markdown"));
    return ResponseEntity.ok()
        .contentType(
            new MediaType(
                "text", format.equals("markdown") ? "markdown" : "plain", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("script-" + id + (format.equals("markdown") ? ".md" : ".txt"))
                .build()
                .toString())
        .body(content);
  }

  @GetMapping("/dashboard")
  Map<String, Object> dashboard() {
    return tasks.dashboard();
  }

  @GetMapping("/admin/tasks")
  List<Map<String, Object>> adminTasks(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String status) {
    return tasks.list(search, status, true);
  }
}
