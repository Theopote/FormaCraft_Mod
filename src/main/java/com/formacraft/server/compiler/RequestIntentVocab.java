package com.formacraft.server.compiler;

import com.formacraft.common.json.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
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
                    throw new IllegalStateException("Missing shared request intent vocabulary: " + RESOURCE);
                }
                JsonObject data = JsonUtil.get().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                JsonElement schema = data == null ? null : data.get("schema");
                if (schema == null || !schema.isJsonPrimitive()
                        || !"formacraft.request_intent_vocab.v1".equals(schema.getAsString())) {
                    throw new IllegalStateException("Invalid shared request intent vocabulary schema: " + RESOURCE);
                }
                CACHED = data;
            } catch (IOException | JsonParseException e) {
                throw new IllegalStateException("Cannot load shared request intent vocabulary: " + RESOURCE, e);
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
}
