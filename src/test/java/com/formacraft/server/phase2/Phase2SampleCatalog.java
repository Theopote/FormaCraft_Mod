package com.formacraft.server.phase2;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Phase2SampleCatalog {
    public static final String ROOT = "/phase2/samples/";

    private Phase2SampleCatalog() {}

    public static List<String> catalogIds() {
        String json = readUtf8(ROOT + "catalog.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray samples = root.getAsJsonArray("samples");
        List<String> ids = new ArrayList<>();
        for (JsonElement e : samples) {
            ids.add(e.getAsString());
        }
        return List.copyOf(ids);
    }

    public static Phase2SampleManifest loadManifest(String id) {
        String json = readUtf8(ROOT + id + "/sample.json");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        List<String> focus = new ArrayList<>();
        if (o.has("focus") && o.get("focus").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("focus")) {
                focus.add(e.getAsString());
            }
        }
        Phase2SampleManifest.Checks checks = Phase2SampleManifest.Checks.fromMap(
                o.has("checks") && o.get("checks").isJsonObject()
                        ? jsonObjectToMap(o.getAsJsonObject("checks"))
                        : null);
        String golden = o.has("goldenPreviewDigest") && !o.get("goldenPreviewDigest").isJsonNull()
                ? o.get("goldenPreviewDigest").getAsString()
                : null;
        return new Phase2SampleManifest(
                o.get("id").getAsString(),
                text(o, "labelZh"),
                text(o, "labelEn"),
                text(o, "prompt"),
                List.copyOf(focus),
                text(o, "planFile"),
                checks,
                golden == null || golden.isBlank() ? null : golden
        );
    }

    public static LlmPlan loadPlan(Phase2SampleManifest manifest) {
        String planFile = manifest.planFile() == null || manifest.planFile().isBlank()
                ? "plan.json"
                : manifest.planFile();
        String json = readUtf8(ROOT + manifest.id() + "/" + planFile);
        try {
            return LlmPlanParser.parse(json);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse plan for " + manifest.id(), e);
        }
    }

    public static List<Phase2SampleManifest> loadAll() {
        List<Phase2SampleManifest> out = new ArrayList<>();
        for (String id : catalogIds()) {
            out.add(loadManifest(id));
        }
        return List.copyOf(out);
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static Map<String, Object> jsonObjectToMap(JsonObject o) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (var e : o.entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            if (v.isJsonPrimitive()) {
                var p = v.getAsJsonPrimitive();
                if (p.isBoolean()) map.put(e.getKey(), p.getAsBoolean());
                else if (p.isNumber()) map.put(e.getKey(), p.getAsNumber());
                else map.put(e.getKey(), p.getAsString());
            } else {
                map.put(e.getKey(), v.toString());
            }
        }
        return map;
    }

    private static String readUtf8(String classpath) {
        try (InputStream in = Phase2SampleCatalog.class.getResourceAsStream(classpath)) {
            Objects.requireNonNull(in, "missing resource: " + classpath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + classpath, e);
        }
    }
}
