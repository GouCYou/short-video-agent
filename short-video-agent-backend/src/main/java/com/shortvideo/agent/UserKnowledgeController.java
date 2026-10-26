package com.shortvideo.agent;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
class UserKnowledgeController {
  private final UserKnowledgeService knowledge;
  private final KnowledgeIngestionService ingestion;

  UserKnowledgeController(UserKnowledgeService knowledge, KnowledgeIngestionService ingestion) {
    this.knowledge = knowledge;
    this.ingestion = ingestion;
  }

  @GetMapping
  List<Map<String, Object>> list() {
    return knowledge.list();
  }

  @GetMapping("/health")
  Map<String, Object> health() {
    return knowledge.health();
  }

  @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> upload(
      @RequestParam MultipartFile file,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String sourceUrl) {
    return knowledge.upload(file, category, sourceUrl);
  }

  @PostMapping("/text")
  Map<String, Object> add(@RequestBody Map<String, Object> body) {
    return knowledge.add(body);
  }

  @PostMapping("/restore-defaults")
  List<Map<String, Object>> restore() {
    return knowledge.restore();
  }

  @PostMapping("/index-all")
  Map<String, Object> indexAll() {
    return knowledge.indexAll();
  }

  @PostMapping("/search")
  List<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
    return knowledge.search(body);
  }

  @GetMapping("/{id}")
  Map<String, Object> get(@PathVariable String id) {
    return knowledge.get(id);
  }

  @PutMapping("/{id}")
  Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return knowledge.update(id, body);
  }

  @PostMapping("/{id}/reindex")
  Map<String, Object> reindex(@PathVariable String id) {
    return knowledge.reindex(id);
  }

  @DeleteMapping("/{id}")
  void remove(@PathVariable String id) {
    knowledge.remove(id);
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> batch(
      @RequestParam List<MultipartFile> files,
      @RequestParam(defaultValue = "通用") String category,
      @RequestParam(defaultValue = "700") int chunkSize,
      @RequestParam(defaultValue = "100") int chunkOverlap) {
    return ingestion.batch(files, category, chunkSize, chunkOverlap);
  }

  @GetMapping("/export")
  Map<String, Object> export() {
    return ingestion.export();
  }

  @PostMapping("/preview")
  Map<String, Object> preview(@RequestBody Map<String, Object> body) {
    return knowledge.preview(body);
  }
}
