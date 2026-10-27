package com.shortvideo.agent;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/model-settings")
class ModelSettingsController {
  private final AiClient ai;
  ModelSettingsController(AiClient ai){this.ai=ai;}
  private Map<String,Object> safe(Map<String,Object> value){
    var result=new LinkedHashMap<String,Object>();
    for(String key:List.of("model","baseUrl","maskedKey","hasKey","revision","updatedAt","format"))
      if(value.containsKey(key))result.put(key,value.get(key));
    return result;
  }
  @GetMapping Map<String,Object> get(){return safe(ai.modelSettings());}
  @PutMapping Map<String,Object> save(@RequestBody Map<String,Object> body){
    if(!Set.of("model","baseUrl","apiKey","revision").containsAll(body.keySet()))throw ApiException.bad("配置包含未知字段");
    return safe(ai.post("/internal/model-settings",body));
  }
}
