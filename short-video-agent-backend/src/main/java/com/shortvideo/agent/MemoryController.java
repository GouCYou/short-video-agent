package com.shortvideo.agent;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memory")
class MemoryController {
  private final MemoryService memory;

  MemoryController(MemoryService memory) {
    this.memory = memory;
  }

  @GetMapping
  Map<String, Object> get() {
    return memory.get();
  }

  @PutMapping
  Map<String, Object> update(@RequestBody Map<String, Object> body) {
    return memory.update(body);
  }

  @DeleteMapping
  void clear() {
    memory.clear();
  }
}
