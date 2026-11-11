package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「吊脚楼」prompts must not lock onto {@code suspension_bridge} just because they mention 吊桥.
 * Rewrite the misrouted STRUCTURE into a stilts-supported shelter mass.
 */
final class StiltShelterMisrouteRepair {
    private StiltShelterMisrouteRepair() {}

    private static final List<String> STILT_SHELTER_CUES = List.of(
            "吊脚楼", "吊脚", "避难所", "stilt house", "stilted", "pile dwelling", "干栏");

    private static final List<String> TRUE_BRIDGE_CUES = List.of(
            "悬索", "斜拉", "金门", "golden gate", "suspension bridge", "cable-stayed",
            "桥塔", "主缆", "deck span");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = intentCorpus(plan);
        if (!containsAny(corpus, STILT_SHELTER_CUES)) {
            return plan;
        }
        if (containsAny(corpus, TRUE_BRIDGE_CUES)) {
            return plan;
        }
        if (!hasSuspensionBridgeTypology(plan)) {
            return plan;
        }

        Component bridge = findSuspensionBridge(plan);
        if (bridge == null || bridge.dimensions() == null) {
            return plan;
        }

        // Prefer a compact shelter footprint — ignore bridge span×deck envelopes.
        int width = Math.min(16, Math.max(7, Math.min(bridge.dimensions().width(), bridge.dimensions().depth())));
        int depth = Math.min(14, Math.max(7, width - 1));
        int height = Math.min(10, Math.max(5, bridge.dimensions().height() / 3));

        String wall = resolveMaterial(plan, "minecraft:spruce_planks",
                "surface", "primary_wood", "wall", "body");
        String accent = resolveMaterial(plan, "minecraft:mud_bricks",
                "accent", "masonry", "band", "wall_accent");
        String frame = resolveMaterial(plan, "minecraft:spruce_log",
                "frame", "primary_structure", "tower", "structural");
        String scrap = resolveMaterial(plan, "minecraft:iron_block",
                "plate", "metal_sheet", "metal", "ornament");
        String roof = resolveMaterial(plan, wall, "roof", "surface", "deck");
        String posts = resolveMaterial(plan, "minecraft:spruce_fence",
                "railing", "tower", "primary_structure");

        Map<String, Object> massParams = new HashMap<>();
        massParams.put("component_id", "stilt_shelter_main");
        massParams.put("building_id", "stilt_shelter_main");
        massParams.put("anchor_mode", "center");
        massParams.put("hollow", true);
        massParams.put("wall_thickness", 1);
        massParams.put("wall_block", wall);
        massParams.put("accent_block", accent);
        massParams.put("floor_block", wall);
        massParams.put("roof_type", "gable");
        massParams.put("roof_block", roof);
        massParams.put("foundation_type", "stilts");
        massParams.put("foundation_mode", "STILTS");
        massParams.put("window_ratio", 0.2);
        massParams.put("void_ratio", 0.2);
        massParams.put("wall_pattern", "banded");
        massParams.put("band_blocks", List.of(accent, scrap));

        Map<String, Object> stiltsParams = new HashMap<>();
        stiltsParams.put("component_id", "stilt_posts");
        stiltsParams.put("host_id", "stilt_shelter_main");
        stiltsParams.put("foundation_type", "stilts");
        stiltsParams.put("uneven", true);
        stiltsParams.put("material", posts);
        stiltsParams.put("wall_block", frame);
        stiltsParams.put("height", Math.max(4, Math.min(10, height)));
        stiltsParams.put("anchor_mode", "center");

        Map<String, Object> roofParams = new HashMap<>();
        roofParams.put("component_id", "stilt_shelter_roof");
        roofParams.put("host_id", "stilt_shelter_main");
        roofParams.put("roof_type", "gable");
        roofParams.put("roof_block", roof);
        roofParams.put("overhang", 1);
        roofParams.put("roof_dormers", false);
        roofParams.put("anchor_mode", "min_corner");

        List<Component> rebuilt = List.of(
                new Component("MASS_MAIN", "main", new Vec3i(0, stiltsParams.get("height") instanceof Number n
                        ? n.intValue() : 5, 0),
                        new Dimensions(width, depth, height),
                        List.of("asymmetric_stilt_shelter", "stilts", "refuge"),
                        massParams),
                new Component("FOUNDATION", "main", new Vec3i(0, 0, 0),
                        new Dimensions(width, depth, stiltsParams.get("height") instanceof Number n
                                ? n.intValue() : 5),
                        List.of("uneven_stilts", "wood_posts"),
                        stiltsParams),
                new Component("ROOF", "main",
                        new Vec3i(-1, (stiltsParams.get("height") instanceof Number n ? n.intValue() : 5) + height,
                                -1),
                        new Dimensions(width + 2, depth + 2, 3),
                        List.of("gable"),
                        roofParams)
        );

        FormacraftMod.LOGGER.info(
                "StiltShelterMisrouteRepair: rewrite suspension_bridge → stilts shelter {}x{}x{}",
                width, depth, height);
        Map<String, Object> hints = plan.proportionHints() == null
                ? new HashMap<>()
                : new HashMap<>(plan.proportionHints());
        hints.put("typology", "stilt_shelter");
        hints.remove("span_to_tower_height");
        hints.remove("cable_sag_ratio");
        return new LlmPlan(
                plan.mode(),
                plan.styleProfile() == null ? "Industrial_Structural" : plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                rebuilt,
                plan.genome(),
                plan.styleAttributes(),
                hints,
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
                List.of("asymmetric_stilt_shelter", "uneven_wood_stilts"),
                plan.enrichmentGuard(),
                plan.planMeta(),
                plan.designSources());
    }

    private static boolean hasSuspensionBridgeTypology(LlmPlan plan) {
        return findSuspensionBridge(plan) != null;
    }

    private static Component findSuspensionBridge(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            for (String key : List.of("typology_id", "typology", "structural_typology",
                    "reference_landmark")) {
                Object v = c.params().get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("suspension_bridge")) {
                    return c;
                }
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && f.toLowerCase(Locale.ROOT).contains("suspension_bridge")) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    private static String resolveMaterial(LlmPlan plan, String fallback, String... keys) {
        if (plan.styleAttributes() != null) {
            String fromPalette = plan.styleAttributes().resolvePaletteBlock(keys);
            if (fromPalette != null && !fromPalette.isBlank()) {
                return fromPalette;
            }
        }
        return fallback;
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.styleProfile() != null) sb.append(plan.styleProfile()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(text).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String corpus, List<String> cues) {
        for (String cue : cues) {
            if (cue != null && !cue.isBlank() && corpus.contains(cue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
