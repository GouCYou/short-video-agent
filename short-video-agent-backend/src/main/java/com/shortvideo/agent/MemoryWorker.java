package com.shortvideo.agent;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class MemoryWorker {
  private final MemoryService memory;
  private final boolean enabled;

  MemoryWorker(MemoryService memory, @Value("${app.worker-enabled:true}") boolean enabled) {
    this.memory = memory;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 15000, scheduler = "memoryScheduler")
  void tick() {
    if (!enabled) return;
    try {
      memory.refreshOne();
    } catch (Exception ex) {
      LoggerFactory.getLogger(MemoryWorker.class)
          .warn("Memory update deferred: {}", ex.getClass().getSimpleName());
    }
  }
}
