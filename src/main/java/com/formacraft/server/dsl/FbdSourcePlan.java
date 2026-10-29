package com.formacraft.server.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formacraft.common.dsl.FbdLimits;
import com.formacraft.common.llm.dto.LlmPlan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/**
 * Sanitize and persist FBD source plans for building Memory / replace edits.
 * Strips server compilation fields and non-FBD components.
 */
public final class FbdSourcePlan {
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private FbdSourcePlan() {}

    public static boolean hasProgram(LlmPlan plan) {
        return FbdRepairLoop.hasProgram(plan);
    }

    /** Returns canonical JSON, or null if the plan has no fbd_program / fails sanitize. */
    public static String sanitizeToJson(LlmPlan plan) {
        if (!hasProgram(plan)) return null;
        try {
            ObjectNode root = JSON.valueToTree(plan);
            stripServerFields(root);
            var componentsNode = root.get("components");
            if (componentsNode == null || !componentsNode.isArray()) return null;
            ArrayNode kept = JSON.createArrayNode();
            for (var c : componentsNode) {
                if (c != null && c.isObject() && c.path("params").has("fbd_program")) {
                    kept.add(c);
                }
            }
            if (kept.isEmpty()) return null;
            root.set("components", kept);
            root.put("plan_status", "ok");
            root.remove("capability_gap");
            root.remove("error");
            byte[] bytes = JSON.writeValueAsBytes(root);
            if (bytes.length > FbdLimits.DEFAULT.bytes()) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static LlmPlan parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > FbdLimits.DEFAULT.bytes()) return null;
            ObjectNode root = (ObjectNode) JSON.readTree(json);
            stripServerFields(root);
            return JSON.treeToValue(root, LlmPlan.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String fingerprint(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return null;
        }
    }

    private static void stripServerFields(ObjectNode root) {
        if (root == null) return;
        var components = root.get("components");
        if (components == null || !components.isArray()) return;
        for (var c : components) {
            if (c == null || !c.isObject()) continue;
            var params = c.get("params");
            if (params == null || !params.isObject()) continue;
            ObjectNode p = (ObjectNode) params;
            p.remove(FbdPlanLowering.COMPILED);
            p.remove(FbdPlanLowering.SOURCE);
            // Jackson may serialize unknown keys; drop any _server_fbd_* leftovers.
            Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = p.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey() != null && e.getKey().startsWith("_server_fbd_")) it.remove();
            }
        }
    }
}
