package com.shortvideo.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RevisionTest {
  @Test
  void openingRevisionPreservesAllOtherFields() {
    var old =
        new LinkedHashMap<String, Object>(
            Map.of(
                "opening",
                "before",
                "caption",
                "original caption",
                "titles",
                List.of("original title")));
    var next =
        new LinkedHashMap<String, Object>(
            Map.of(
                "opening",
                "after",
                "caption",
                "unrequested change",
                "titles",
                List.of("unrequested title")));
    JobWorker.preserveRevision(old, next, "opening");
    assertThat(next)
        .containsEntry("opening", "after")
        .containsEntry("caption", "original caption")
        .containsEntry("titles", List.of("original title"));
  }

  @Test
  void segmentRevisionPreservesOtherSegmentsAndTopLevelFields() {
    var old =
        new LinkedHashMap<String, Object>(
            Map.of(
                "opening",
                "before",
                "segments",
                List.of(Map.of("narration", "first"), Map.of("narration", "second"))));
    var next =
        new LinkedHashMap<String, Object>(
            Map.of(
                "opening",
                "unrequested",
                "segments",
                List.of(
                    Map.of("narration", "unrequested first"),
                    Map.of("narration", "revised second"))));
    JobWorker.preserveRevision(old, next, "1");
    assertThat(next.get("opening")).isEqualTo("before");
    assertThat(next.get("segments"))
        .isEqualTo(List.of(Map.of("narration", "first"), Map.of("narration", "revised second")));
  }
}
