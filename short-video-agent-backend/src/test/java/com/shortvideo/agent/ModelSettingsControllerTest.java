package com.shortvideo.agent;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class ModelSettingsControllerTest {
 @Test void onlyAdministratorsCanReadOrReplaceAndResponsesNeverForwardRawKeys() throws Exception {
  var ai=mock(AiClient.class);var auth=mock(AuthService.class);var mapper=new ObjectMapper();
  when(auth.authenticate("user-token")).thenReturn(new AuthService.Account("user","user",null,"用户","USER",true,null,null,0));
  when(auth.authenticate("admin-token")).thenReturn(new AuthService.Account("admin","admin",null,"管理","ADMIN",true,null,null,0));
  var view=Map.<String,Object>of("model","demo-model","baseUrl","https://api.example.com/v1","maskedKey","sk-abc••••••••1234","hasKey",true,"revision",2,"apiKey","never-forward-this-key");
  when(ai.modelSettings()).thenReturn(view);when(ai.post(eq("/internal/model-settings"),any())).thenReturn(view);
  var mvc=MockMvcBuilders.standaloneSetup(new ModelSettingsController(ai)).addFilters(new AuthFilter(auth,mapper)).build();
  mvc.perform(get("/api/admin/model-settings")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/model-settings").header("Authorization","Bearer user-token")).andExpect(status().isForbidden());
  mvc.perform(put("/api/admin/model-settings").header("Authorization","Bearer user-token").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
  var result=mvc.perform(get("/api/admin/model-settings").header("Authorization","Bearer admin-token")).andExpect(status().isOk()).andReturn();
  assertThat(result.getResponse().getContentAsString()).contains("maskedKey").doesNotContain("apiKey","never-forward-this-key");
  mvc.perform(put("/api/admin/model-settings").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"new-model\",\"baseUrl\":\"https://api.example.com/v1\",\"revision\":2}")).andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").doesNotExist());
  verify(ai,times(1)).post(eq("/internal/model-settings"),any());
 }
}
