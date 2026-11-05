package com.formacraft.server.compiler;

import com.formacraft.common.json.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared request_intent vocabulary loaded from
 * {@code assets/formacraft/request_intent/request_intent_vocab_v1.json}.
 * Python loads the same file — keep markers in sync there, not duplicated here.
 */
final class RequestIntentVocab {
    private static final String RESOURCE =
            "assets/formacraft/request_intent/request_intent_vocab_v1.json";

    private static volatile JsonObject CACHED;

    private RequestIntentVocab() {}

    static JsonObject root() {
        JsonObject local = CACHED;
        if (local != null) {
            return local;
        }
        synchronized (RequestIntentVocab.class) {
            if (CACHED != null) {
                return CACHED;
            }
            try (InputStream in = RequestIntentVocab.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    CACHED = fallback();
                } else {
                    CACHED = JsonUtil.get().fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    if (CACHED == null) {
                        CACHED = fallback();
                    }
                }
            } catch (Exception e) {
                CACHED = fallback();
            }
            return CACHED;
        }
    }

    static List<String> negationPrefixes() {
        return stringList(root().getAsJsonArray("negation_prefixes"));
    }

    static double conf(String key, double def) {
        JsonObject c = root().getAsJsonObject("confidence");
        if (c == null || !c.has(key)) {
            return def;
        }
        try {
            return c.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    static JsonObject buildingClass(String name) {
        JsonObject classes = root().getAsJsonObject("building_classes");
        if (classes == null) {
            return null;
        }
        return classes.getAsJsonObject(name);
    }

    static List<String> stringList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (JsonElement el : arr) {
            if (el != null && el.isJsonPrimitive()) {
                String s = el.getAsString();
                if (s != null && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static List<Map<String, String>> roofTypes() {
        List<Map<String, String>> out = new ArrayList<>();
        JsonArray arr = root().getAsJsonArray("roof_types");
        if (arr == null) {
            return out;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String phrase = o.has("phrase") ? o.get("phrase").getAsString() : null;
            String canonical = o.has("canonical") ? o.get("canonical").getAsString() : null;
            if (phrase == null || canonical == null) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("phrase", phrase);
            row.put("canonical", canonical);
            out.add(row);
        }
        return out;
    }

    private static JsonObject fallback() {
        // Minimal fallback mirroring Python — used only if the asset is missing.
        String json = """
                {
                  "negation_prefixes": ["不要","无","禁止","no ","without ","not "],
                  "building_classes": {
                    "tower_like": {"strong_markers": ["佛塔","古塔","宝塔","pagoda","stupa","塔刹"]},
                    "gate": {"strong_markers": ["山门","牌坊","paifang","gatehouse","寺庙山门"]},
                    "garden": {
                      "strong_markers": ["江南园林","日式庭院","园林","courtyard compound"],
                      "weak_markers": ["庭院","courtyard","garden"],
                      "weak_suppressors": ["别墅","villa","城堡","castle","住宅","house","图书馆","library"]
                    },
                    "temple": {"strong_markers": ["道观","寺庙","庙宇","大殿","殿堂"]}
                  },
                  "roof_types": [
                    {"phrase":"歇山","canonical":"xieshan"},
                    {"phrase":"重檐","canonical":"double_eave_hip"},
                    {"phrase":"gable","canonical":"gable"}
                  ],
                  "forbids": {
                    "windows": ["不要窗","无窗","no windows"],
                    "entrance": ["不要门","无门","no door"]
                  },
                  "forbid_false_friends": {
                    "entrance": ["无门槛","没门槛","no threshold"]
                  },
                  "tower_subtypes": {
                    "tibetan_stupa": ["藏式","白塔","覆钵","相轮","tibetan","chorten"]
                  },
                  "temple_compound_markers": ["建筑群","院落","厢房","配殿"],
                  "confidence": {
                    "strong_hit": 0.9,
                    "weak_hit": 0.45,
                    "scaffold_min": 0.75
                  }
                }
                """;
        return JsonUtil.get().fromJson(json, JsonObject.class);
    }
}
