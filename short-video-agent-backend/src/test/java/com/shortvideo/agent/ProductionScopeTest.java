package com.shortvideo.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionScopeTest {
  @Test
  void eachSectionScopePreservesEveryUnrelatedTopLevelField() {
    for (String scope : List.of("opening", "titles", "voiceover", "shooting", "editing")) {
      var old = ConversationIntegrationTest.production();
      var proposed = ConversationIntegrationTest.production();
      proposed.put("opening", "new opening");
      proposed.put("title", "new title");
      proposed.put("titles", List.of("new alternatives"));
      proposed.put("caption", "unrelated caption change");
      proposed.put(
          "voiceoverPlan",
          Map.of(
              "approach",
              "new approach",
              "tone",
              "new tone",
              "pace",
              "new pace",
              "recordingTips",
              List.of("new tip"),
              "script",
              "new script"));
      proposed.put(
          "shootingPlan",
          Map.of(
              "approach",
              "new shoot",
              "locations",
              List.of("new place"),
              "equipment",
              List.of("new camera"),
              "props",
              List.of(),
              "schedule",
              List.of("new schedule")));
      proposed.put(
          "editingPlan",
          Map.of(
              "approach",
              "new edit",
              "rhythm",
              "new rhythm",
              "music",
              "new music",
              "soundEffects",
              List.of(),
              "subtitleStyle",
              "new style",
              "steps",
              List.of("new step")));
      var merged = ProductionValidator.mergeRevision(old, proposed, scope);
      Set<String> allowed =
          switch (scope) {
            case "opening" -> Set.of("opening");
            case "titles" -> Set.of("title", "titles");
            case "voiceover" -> Set.of("voiceoverPlan");
            case "shooting" -> Set.of("shootingPlan");
            default -> Set.of("editingPlan");
          };
      for (String field : old.keySet())
        assertThat(merged.get(field))
            .as(scope + ":" + field)
            .isEqualTo(allowed.contains(field) ? proposed.get(field) : old.get(field));
    }
  }

  @Test
  void segmentAndDurationScopesRetainStableIdsOrderAndUnchangedFields() {
    var old = ConversationIntegrationTest.production();
    var first =
        new LinkedHashMap<String, Object>(Json.map(((List<?>) old.get("segments")).getFirst()));
    var second = new LinkedHashMap<String, Object>(first);
    second.put("id", "cb3e03eb-318a-4f50-ac50-75f479e7b66a");
    second.put("narration", "original second");
    old.put("segments", List.of(first, second));
    var proposed = ConversationIntegrationTest.production();
    var changedFirst = new LinkedHashMap<>(first);
    changedFirst.put("duration", 15.0);
    changedFirst.put("narration", "unexpected first edit");
    var changedSecond = new LinkedHashMap<>(second);
    changedSecond.put("duration", 45.0);
    changedSecond.put("narration", "requested second edit");
    proposed.put("segments", List.of(changedSecond, changedFirst));
    var merged = ProductionValidator.mergeRevision(old, proposed, "segment:" + second.get("id"));
    assertThat(merged.get("segments")).isEqualTo(List.of(first, changedSecond));
    var timed = ProductionValidator.mergeRevision(old, proposed, "duration");
    var segments = (List<?>) timed.get("segments");
    assertThat(Json.map(segments.getFirst()))
        .containsEntry("narration", first.get("narration"))
        .containsEntry("duration", 15.0)
        .containsEntry("id", first.get("id"));
    assertThat(Json.map(segments.getLast()))
        .containsEntry("narration", "original second")
        .containsEntry("duration", 45.0);
    assertThatThrownBy(() -> ProductionValidator.mergeRevision(old, proposed, "unknown"))
        .isInstanceOf(ApiException.class);
  }
}
