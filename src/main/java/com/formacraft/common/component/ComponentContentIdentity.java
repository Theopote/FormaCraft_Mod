package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Read-only equivalence for validated, self-contained definitions. Never grants permissions. */
public final class ComponentContentIdentity {
    private ComponentContentIdentity() {}
    public enum Match { EXACT_CONTENT, SAME_STRUCTURE, DIFFERENT }
    public record Fingerprints(String structure, String configuration) {}
    private record Snapshot(String structure, String configuration) {}

    public static Fingerprints fingerprints(ComponentDefinition definition) {
        Snapshot s = snapshot(definition);
        return new Fingerprints(hash("structure-v1", s.structure), hash("configuration-v1", s.configuration));
    }

    /** Exact canonical comparison also avoids treating a digest collision as equality. */
    public static Match compare(ComponentDefinition first, ComponentDefinition second) {
        Snapshot a = snapshot(first), b = snapshot(second);
        if (!a.structure.equals(b.structure)) return Match.DIFFERENT;
        return a.configuration.equals(b.configuration) ? Match.EXACT_CONTENT : Match.SAME_STRUCTURE;
    }

    private static Snapshot snapshot(ComponentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        JsonObject full = JsonUtil.get().toJsonTree(definition).getAsJsonObject();
        for (String field : List.of("id", "name", "tags", "category", "type", "culturalStyle", "geometryArchetype", "archetypeRef"))
            full.remove(field);
        JsonObject structure = new JsonObject();
        for (String field : List.of("size", "anchor", "blocks", "sockets", "socketPlacements"))
            if (full.has(field)) structure.add(field, full.get(field).deepCopy());
        normalizeBlocks(full, false);
        normalizeBlocks(structure, true);
        normalizeSockets(full);
        normalizeSockets(structure);
        stripFromArray(structure, "socketPlacements", List.of("adaptMode"));
        sortArray(full, "allowed_facing");
        for (JsonObject root : List.of(full, structure))
            for (String field : List.of("blocks", "sockets", "socketPlacements")) sortArray(root, field);
        return new Snapshot(canonical(structure), canonical(full));
    }

    private static void normalizeBlocks(JsonObject root, boolean structureOnly) {
        if (!root.has("blocks") || !root.get("blocks").isJsonArray()) return;
        for (JsonElement element : root.getAsJsonArray("blocks")) {
            JsonObject block = element.getAsJsonObject();
            if (structureOnly) { block.remove("semantic"); block.remove("carveRole"); }
            if (block.has("block") && !block.get("block").isJsonNull())
                block.addProperty("block", blockState(block.get("block").getAsString()));
        }
    }

    private static void normalizeSockets(JsonObject root) {
        if (!root.has("sockets") || !root.get("sockets").isJsonArray()) return;
        for (JsonElement socket : root.getAsJsonArray("sockets")) sortArray(socket.getAsJsonObject(), "tags");
    }

    private static void stripFromArray(JsonObject root, String field, List<String> removed) {
        if (!root.has(field) || !root.get(field).isJsonArray()) return;
        for (JsonElement entry : root.getAsJsonArray(field))
            for (String key : removed) entry.getAsJsonObject().remove(key);
    }

    private static String blockState(String text) {
        int start = text.indexOf('[');
        if (start < 0 || !text.endsWith("]")) return text;
        String body = text.substring(start + 1, text.length() - 1);
        String[] properties = body.split(",", -1);
        Set<String> keys = new HashSet<>();
        for (String property : properties) {
            String[] pair = property.split("=", -1);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || !keys.add(pair[0]))
                throw new IllegalArgumentException("Invalid block state properties");
        }
        Arrays.sort(properties);
        return text.substring(0, start) + "[" + String.join(",", properties) + "]";
    }

    private static void sortArray(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) return;
        List<JsonElement> values = new ArrayList<>();
        object.getAsJsonArray(field).forEach(values::add);
        values.sort(Comparator.comparing(ComponentContentIdentity::canonical));
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        object.add(field, result);
    }

    private static String canonical(JsonElement value) { return ordered(value).toString(); }
    private static JsonElement ordered(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(key -> result.add(key, ordered(value.getAsJsonObject().get(key))));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(item -> result.add(ordered(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static String hash(String version, String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((version + "\n" + content).getBytes(StandardCharsets.UTF_8));
            return version + ":sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
