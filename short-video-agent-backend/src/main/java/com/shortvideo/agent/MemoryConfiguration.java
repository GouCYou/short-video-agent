package com.shortvideo.agent;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class MemoryConfiguration {
  @Bean(name = "memoryScheduler")
  ThreadPoolTaskScheduler memoryScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("memory-");
    return scheduler;
  }

  @Bean(name = "taskScheduler")
  ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("agent-");
    return scheduler;
  }
}
