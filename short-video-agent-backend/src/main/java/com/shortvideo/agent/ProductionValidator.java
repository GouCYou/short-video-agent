package com.shortvideo.agent;

import java.util.*;

/**
 * Shared boundary checks matching Python conversation_models.py, including manual version edits.
 */
final class ProductionValidator {
  static final Set<String> BRIEF_FIELDS =
      Set.of(
          "topic",
          "direction",
          "audience",
          "platform",
          "duration",
          "style",
          "productionMode",
          "equipment",
          "requirements");
  static final Set<String> KINDS = Set.of("TOPIC", "DIRECTION", "AUDIENCE", "STYLE", "PRODUCTION");

  private ProductionValidator() {}

  static Map<String, Object> defaultBrief() {
    var brief = new LinkedHashMap<String, Object>();
    for (String field : BRIEF_FIELDS) brief.put(field, "");
    brief.put("platform", "抖音");
    brief.put("duration", 60);
    brief.put("style", "自然亲切");
    return brief;
  }

  static void exact(Map<String, Object> value, Set<String> keys, String label) {
    if (!value.keySet().equals(keys)) throw ApiException.bad(label + "字段缺失或包含未知字段");
  }

  static void text(Object value, String label, int max, boolean blank) {
    TaskService.validateText(value, label, max, blank);
  }

  static void strings(Object value, String label, int min, int max) {
    TaskService.validateTextList(value, label, min, max);
  }

  static void brief(Map<String, Object> brief) {
    exact(brief, BRIEF_FIELDS, "创作需求");
    for (String field : BRIEF_FIELDS)
      if (!field.equals("duration"))
        text(
            brief.get(field),
            field,
            field.equals("requirements") ? 6000 : field.equals("platform") ? 100 : 2000,
            !field.equals("platform"));
    if (!(brief.get("duration") instanceof Integer)) throw ApiException.bad("需求时长必须为整数");
    Json.integer(brief, "duration", 0, 10, 600);
  }

  static void content(Map<String, Object> p) {
    exact(
        p,
        Set.of(
            "title",
            "summary",
            "positioning",
            "audienceAnalysis",
            "titles",
            "opening",
            "segments",
            "ending",
            "caption",
            "tags",
            "shootingPlan",
            "voiceoverPlan",
            "editingPlan",
            "qualityNotes"),
        "制作方案");
    text(p.get("title"), "title", 300, false);
    for (String field :
        List.of("summary", "positioning", "audienceAnalysis", "opening", "ending", "caption"))
      text(p.get(field), field, field.equals("caption") ? 10000 : 5000, false);
    strings(p.get("titles"), "titles", 1, 10);
    strings(p.get("tags"), "tags", 0, 20);
    strings(p.get("qualityNotes"), "qualityNotes", 0, 30);
    if (!(p.get("segments") instanceof List<?> segments)
        || segments.isEmpty()
        || segments.size() > 30) throw ApiException.bad("制作方案须包含 1 到 30 段脚本");
    Set<String> ids = new HashSet<>();
    for (Object item : segments) {
      var segment = Json.map(item);
      exact(
          segment,
          Set.of(
              "id",
              "duration",
              "visual",
              "narration",
              "subtitle",
              "shotSize",
              "camera",
              "action",
              "voiceDirection",
              "editing",
              "material"),
          "脚本段落");
      String id = Json.required(segment, "id", 36);
      try {
        if (!UUID.fromString(id).toString().equalsIgnoreCase(id))
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException ex) {
        throw ApiException.bad("脚本段落 id 必须为 UUID");
      }
      if (!ids.add(id.toLowerCase(Locale.ROOT))) throw ApiException.bad("脚本段落 id 不可重复");
      Object duration = segment.get("duration");
      if (!(duration instanceof Number number)
          || !Double.isFinite(number.doubleValue())
          || number.doubleValue() <= 0
          || number.doubleValue() > 600) throw ApiException.bad("段落时长必须为大于 0 且不超过 600 的数值");
      for (String field :
          List.of(
              "visual",
              "narration",
              "subtitle",
              "shotSize",
              "camera",
              "action",
              "voiceDirection",
              "editing",
              "material"))
        text(
            segment.get(field),
            field,
            field.equals("narration")
                ? 10000
                : Set.of("visual", "subtitle").contains(field) ? 5000 : 2000,
            field.equals("subtitle"));
    }
    var shooting = Json.map(p.get("shootingPlan"));
    exact(shooting, Set.of("approach", "locations", "equipment", "props", "schedule"), "拍摄方案");
    text(shooting.get("approach"), "shooting.approach", 5000, false);
    for (String field : List.of("locations", "equipment", "props", "schedule"))
      strings(shooting.get(field), "shooting." + field, field.equals("props") ? 0 : 1, 30);
    var voice = Json.map(p.get("voiceoverPlan"));
    exact(voice, Set.of("approach", "tone", "pace", "recordingTips", "script"), "配音方案");
    text(voice.get("approach"), "voiceover.approach", 5000, false);
    text(voice.get("tone"), "voiceover.tone", 2000, false);
    text(voice.get("pace"), "voiceover.pace", 2000, false);
    strings(voice.get("recordingTips"), "voiceover.recordingTips", 1, 30);
    text(voice.get("script"), "voiceover.script", 30000, false);
    var editing = Json.map(p.get("editingPlan"));
    exact(
        editing,
        Set.of("approach", "rhythm", "music", "soundEffects", "subtitleStyle", "steps"),
        "剪辑方案");
    text(editing.get("approach"), "editing.approach", 5000, false);
    for (String field : List.of("rhythm", "music", "subtitleStyle"))
      text(editing.get(field), "editing." + field, 2000, false);
    strings(editing.get("soundEffects"), "editing.soundEffects", 0, 30);
    strings(editing.get("steps"), "editing.steps", 1, 30);
  }

  static List<Map<String, Object>> choices(Object value) {
    if (!(value instanceof List<?> groups) || groups.size() > 5) throw ApiException.bad("选项组格式无效");
    var output = new ArrayList<Map<String, Object>>();
    Set<String> seen = new HashSet<>();
    for (Object item : groups) {
      Map<String, Object> candidate = Json.map(item);
      String kind = Json.required(candidate, "kind", 20);
      if (!KINDS.contains(kind) || !seen.add(kind)) throw ApiException.bad("选项组类型无效或重复");
      String title = Json.required(candidate, "title", 300);
      if (!(candidate.get("options") instanceof List<?> options) || options.size() < 2 || options.size() > 5)
        throw ApiException.bad("每组选项须包含 2 到 5 项");
      var canonical = new ArrayList<Map<String, Object>>();
      Set<String> values = new HashSet<>();
      int index = 0;
      for (Object option : options) {
        var o = Json.map(option);
        String label = Json.required(o, "label", 1);
        if (!label.equals(List.of("A", "B", "C", "D", "E").get(index++)))
          throw ApiException.bad("选项必须从 A 开始按字母顺序排列");
        String optionValue = Json.required(o, "value", 2000);
        if (!values.add(optionValue)) throw ApiException.bad("选项内容不可重复");
        canonical.add(
            Map.of(
                "id",
                Json.id(),
                "label",
                label,
                "title",
                Json.required(o, "title", 300),
                "description",
                Json.required(o, "description", 2000),
                "value",
                optionValue));
      }
      var group = new LinkedHashMap<String, Object>();
      group.put("id", Json.id());
      group.put("kind", kind);
      group.put("title", title);
      group.put("options", canonical);
      group.put("selectedOptionId", null);
      group.put("customValue", null);
      group.put("status", "OPEN");
      output.add(group);
    }
    return output;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> sources(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 100) throw ApiException.bad("资料引用格式无效");
    for (Object item : list) {
      var source = Json.map(item);
      exact(
          source,
          Set.of("documentId", "documentName", "chunkId", "text", "score", "sourceUrl"),
          "资料引用");
      for (String key : List.of("documentId", "documentName", "chunkId", "text", "sourceUrl"))
        text(
            source.get(key),
            "source." + key,
            key.equals("text") ? 10000 : key.equals("sourceUrl") ? 2000 : 1000,
            true);
      if (!(source.get("score") instanceof Number number) || !Double.isFinite(number.doubleValue()))
        throw ApiException.bad("资料引用评分无效");
    }
    return (List<Map<String, Object>>) (List<?>) list;
  }

  static Map<String, Object> mergeRevision(
      Map<String, Object> original, Map<String, Object> proposed, String scope) {
    content(proposed);
    if (scope.equals("ALL")) return proposed;
    content(original);
    var merged = new LinkedHashMap<String, Object>(original);
    Set<String> fields =
        switch (scope) {
          case "opening" -> Set.of("opening");
          case "titles" -> Set.of("title", "titles");
          case "voiceover" -> Set.of("voiceoverPlan");
          case "shooting" -> Set.of("shootingPlan");
          case "editing" -> Set.of("editingPlan");
          default -> Set.of();
        };
    if (!fields.isEmpty()) {
      for (String field : fields) merged.put(field, proposed.get(field));
      return merged;
    }
    var previous = (List<?>) original.get("segments");
    var replacements = (List<?>) proposed.get("segments");
    var byId = new HashMap<String, Map<String, Object>>();
    for (Object value : replacements) {
      var segment = Json.map(value);
      byId.put(segment.get("id").toString(), segment);
    }
    var segments = new ArrayList<Map<String, Object>>();
    String target = scope.startsWith("segment:") ? scope.substring(8) : scope;
    boolean found = false;
    for (Object value : previous) {
      var segment = Json.map(value);
      String id = segment.get("id").toString();
      var next = byId.get(id);
      var copy = new LinkedHashMap<String, Object>(segment);
      if (scope.equals("duration")) {
        if (next == null) throw ApiException.bad("时长调整必须保留全部分镜 ID");
        copy.put("duration", next.get("duration"));
      } else if (id.equals(target)) {
        if (next == null) throw ApiException.bad("AI 未返回指定分镜");
        copy = new LinkedHashMap<>(next);
        found = true;
      }
      segments.add(copy);
    }
    if (!scope.equals("duration") && !found) throw ApiException.bad("修改范围不存在");
    merged.put("segments", segments);
    return merged;
  }

  static String briefField(String kind) {
    return switch (kind) {
      case "TOPIC" -> "topic";
      case "DIRECTION" -> "direction";
      case "AUDIENCE" -> "audience";
      case "STYLE" -> "style";
      case "PRODUCTION" -> "productionMode";
      default -> throw ApiException.bad("无效选项类型");
    };
  }
}
