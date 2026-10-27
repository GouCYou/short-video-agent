package com.shortvideo.agent;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/creation-defaults")
class CreationSettingsController {
  private final CreationSettingsService service;
  CreationSettingsController(CreationSettingsService service){this.service=service;}
  @GetMapping Map<String,Object> get(){return service.get();}
  @PutMapping Map<String,Object> save(@RequestBody Map<String,Object> body){return service.save(body);}
  @DeleteMapping Map<String,Object> reset(){return service.reset();}
}
