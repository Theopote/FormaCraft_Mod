package com.formacraft.server.compiler;

import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.request.FormaRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects authoritative {@code plan_meta.request_intent} from the original user
 * request so semantic validators do not depend on LLM-retained features.
 */
public final class RequestIntentInjector {
    private static final Pattern FOOTPRINT = Pattern.compile("(\\d{1,2})\\s*[×xX*]\\s*(\\d{1,2})");

    private RequestIntentInjector() {}

    public static LlmPlan ensure(LlmPlan plan, FormaRequest req) {
        return ensure(plan, BuildingSpecRoutingPolicy.userIntentText(req));
    }

    public static LlmPlan ensure(LlmPlan plan, String userText) {
        if (plan == null) return null;
        String text = userText == null ? "" : userText.trim();
        Map<String, Object> meta = plan.planMeta() == null
                ? new HashMap<>()
                : new HashMap<>(plan.planMeta());
        Object existing = meta.get("request_intent");
        if (existing instanceof Map<?, ?> existingMap
                && existingMap.get("original_text") != null
                && !String.valueOf(existingMap.get("original_text")).isBlank()) {
            // Keep Python-authored authoritative intent; only fill missing text echo.
            if (!meta.containsKey("request_intent_text")) {
                meta.put("request_intent_text", String.valueOf(existingMap.get("original_text")));
            }
            if (!meta.containsKey("building_class") && existingMap.get("building_class") != null) {
                meta.put("building_class", existingMap.get("building_class"));
            }
            return withMeta(plan, meta);
        }
        if (text.isBlank()) {
            return plan;
        }
        Map<String, Object> intent = extract(text);
        meta.put("request_intent", intent);
        meta.put("request_intent_text", text);
        meta.put("building_class", intent.get("building_class"));
        return withMeta(plan, meta);
    }

    static Map<String, Object> extract(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String buildingClass = "generic";
        List<String> markers = new ArrayList<>();
        if (containsAny(lower, text, "佛塔", "古塔", "宝塔", "藏式", "pagoda", "stupa", "塔刹", "重檐")) {
            buildingClass = "tower_like";
        } else if (containsAny(lower, text, "山门", "牌坊", "paifang", "gatehouse", "寺庙山门")) {
            buildingClass = "gate";
        } else if (containsAny(lower, text, "江南园林", "日式庭院", "园林", "庭院", "garden")) {
            buildingClass = "garden";
        } else if (containsAny(lower, text, "道观", "寺庙", "庙宇", "daoist")) {
            buildingClass = "temple";
        }
        for (String m : List.of("佛塔", "古塔", "山门", "牌坊", "园林", "庭院", "道观", "pagoda", "paifang")) {
            if (containsAny(lower, text, m)) markers.add(m);
        }
        String roofType = null;
        if (text.contains("歇山")) roofType = "xieshan";
        else if (text.contains("庑殿")) roofType = "wudian";
        else if (text.contains("重檐")) roofType = "double_eave_hip";
        else if (text.contains("攒尖")) roofType = "pyramid";
        else if (text.contains("悬山")) roofType = "xuanshan";
        else if (text.contains("硬山")) roofType = "yingshan";
        else if (text.contains("平顶") || lower.contains("flat roof")) roofType = "flat";
        else if (text.contains("双坡") || text.contains("人字") || lower.contains("gable")) roofType = "gable";

        int width = 0, depth = 0;
        Matcher m = FOOTPRINT.matcher(text);
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (a >= 4 && b >= 4 && a <= 48 && b <= 48) {
                width = a;
                depth = b;
                break;
            }
        }
        List<String> forbids = new ArrayList<>();
        if (containsAny(lower, text, "不要窗", "无窗", "禁窗", "不开窗", "no windows")) forbids.add("windows");
        if (containsAny(lower, text, "不要门", "无门", "禁门", "no door")) forbids.add("entrance");

        Map<String, Object> materials = new HashMap<>();
        if (text.contains("白墙") || lower.contains("white_concrete")) {
            materials.put("wall", "minecraft:white_concrete");
        }
        if (text.contains("黑瓦") || lower.contains("black_terracotta")) {
            materials.put("roof", "minecraft:black_terracotta");
        }

        Map<String, Object> intent = new HashMap<>();
        intent.put("schema", "formacraft.request_intent.v1");
        intent.put("source", "user_request");
        intent.put("original_text", text);
        intent.put("building_class", buildingClass);
        intent.put("markers", markers);
        intent.put("roof_type", roofType);
        intent.put("roof_explicit", roofType != null);
        if (width > 0 && depth > 0) {
            intent.put("footprint", Map.of("width", width, "depth", depth));
        }
        intent.put("forbids", forbids);
        intent.put("materials", materials);
        intent.put("refuse_residential_autofill", !"generic".equals(buildingClass));
        return intent;
    }

    private static boolean containsAny(String lower, String raw, String... needles) {
        for (String n : needles) {
            if (n == null || n.isEmpty()) continue;
            if (lower.contains(n.toLowerCase(Locale.ROOT)) || raw.contains(n)) return true;
        }
        return false;
    }

    private static LlmPlan withMeta(LlmPlan plan, Map<String, Object> meta) {
        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                plan.components(),
                plan.genome(),
                plan.styleAttributes(),
                plan.proportionHints(),
                plan.alignmentAndSymmetry(),
                plan.targetSlotId(),
                plan.allowedArea(),
                plan.patch(),
                plan.planProgram(),
                plan.planSkeleton(),
                plan.planStatus(),
                plan.error(),
                plan.capabilityGap(),
                plan.playerFidelityNoticeZh(),
                plan.distinguishingFeatures(),
                plan.enrichmentGuard(),
                meta,
                plan.designSources());
    }
}
