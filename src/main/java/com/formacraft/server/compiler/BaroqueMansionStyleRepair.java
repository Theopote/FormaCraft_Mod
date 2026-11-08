package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Baroque mansions must keep rectangular 15×25 massing, mansard roofs, and plinth banding.
 * LLM often labels them {@code Classical_GrecoRoman}, which then triggers temple squaring
 * and classical pilaster window rhythm instead of arched quartz openings + mansard.
 */
final class BaroqueMansionStyleRepair {
    private BaroqueMansionStyleRepair() {}

    private static final List<String> BAROQUE_CUES = List.of(
            "巴洛克", "baroque", "大宅", "宅邸", "府邸", "公馆", "庄园", "马萨", "mansard",
            "折线屋顶", "折线形屋顶", "老虎窗", "mansion");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = requestCorpus(plan);
        if (!containsAny(corpus, BAROQUE_CUES) && !hasBaroqueMarkers(plan)) {
            return plan;
        }

        boolean changed = false;
        String style = plan.styleProfile();
        if (styleLooksClassicalTemple(style)) {
            style = "Legacy_Classical_Facade";
            changed = true;
        }

        List<Component> out = new ArrayList<>(plan.components().size());
        for (Component c : plan.components()) {
            Component next = enrich(plan, c, corpus);
            if (next != c) {
                changed = true;
            }
            out.add(next);
        }

        Map<String, Object> hints = plan.proportionHints() == null
                ? new HashMap<>()
                : new HashMap<>(plan.proportionHints());
        Object typology = hints.get("typology");
        if (typology == null || String.valueOf(typology).toLowerCase(Locale.ROOT).contains("stadium")
                || String.valueOf(typology).toLowerCase(Locale.ROOT).contains("temple")) {
            hints.put("typology", "baroque_mansion");
            changed = true;
        }
        if (!hints.containsKey("roof_specialty")) {
            hints.put("roof_specialty", "mansard_dormer");
            changed = true;
        }

        if (!changed) {
            return plan;
        }
        FormacraftMod.LOGGER.info(
                "BaroqueMansionStyleRepair: preserve mansard/plinth baroque mansion (style={})", style);
        return new LlmPlan(
                plan.mode(), style, plan.anchor(), plan.globalConstraints(), plan.layout(),
                out, plan.genome(), plan.styleAttributes(), hints,
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    private static boolean styleLooksClassicalTemple(String style) {
        if (style == null || style.isBlank()) return false;
        String s = style.toLowerCase(Locale.ROOT);
        return s.contains("grecoroman") || s.contains("greco_roman") || s.contains("stadium")
                || s.contains("pantheon");
    }

    private static boolean hasBaroqueMarkers(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            String id = c.params() == null ? ""
                    : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            if (id.contains("mansion") || id.contains("baroque")) return true;
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && (f.toLowerCase(Locale.ROOT).contains("baroque")
                            || f.toLowerCase(Locale.ROOT).contains("mansard"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Component enrich(LlmPlan plan, Component c, String corpus) {
        if (c == null || c.componentType() == null) return c;
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        boolean changed = false;

        if (type.startsWith("MASS") || "STRUCTURE".equals(type)) {
            if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("base_plinth"))) {
                features.add("base_plinth");
                changed = true;
            }
            if (!params.containsKey("facade_profile") && !params.containsKey("facadeProfile")) {
                params.put("facade_profile", "base_plinth");
                changed = true;
            }
            String plinth = resolvePlinthBlock(plan, corpus);
            if (plinth != null) {
                if (params.putIfAbsent("foundation_block", plinth) == null) changed = true;
                if (params.putIfAbsent("plinth_block", plinth) == null) changed = true;
                if (params.putIfAbsent("base_block", plinth) == null) changed = true;
            }
            String wall = resolveUpperWall(plan);
            if (wall != null && params.putIfAbsent("wall_block", wall) == null) {
                changed = true;
            }
            if (containsAny(corpus, List.of("马萨", "mansard", "折线"))) {
                Object rt = params.get("roof_type");
                if (rt == null) rt = params.get("roofType");
                if (rt == null || !String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
                    params.put("roof_type", "mansard");
                    params.put("preferred_roof_type", "mansard");
                    changed = true;
                }
            }
        }
        if (type.startsWith("ROOF")) {
            Object rt = params.get("roof_type");
            if (rt == null) rt = params.get("roofType");
            if (rt == null || !String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
                params.put("roof_type", "mansard");
                params.put("roof_specialty", "mansard_dormer");
                params.put("roof_dormers", true);
                changed = true;
            }
            if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
                features.add("mansard");
                changed = true;
            }
            String roofBlock = resolveRoofBlock(plan);
            if (roofBlock != null && params.putIfAbsent("roof_block", roofBlock) == null) {
                changed = true;
            }
        }
        if (type.contains("FACADE") || type.contains("WINDOW")) {
            if (containsAny(corpus, List.of("拱形", "arched", "石英"))) {
                Object aspect = params.get("window_aspect");
                if (aspect == null || String.valueOf(aspect).toLowerCase(Locale.ROOT).contains("ribbon")
                        || String.valueOf(aspect).toLowerCase(Locale.ROOT).contains("pilaster")) {
                    params.put("window_aspect", "arched");
                    params.put("window_style", "arched");
                    changed = true;
                }
            }
        }
        if (!changed) {
            return c;
        }
        return new Component(c.componentType(), c.slotId(), c.relativePosition(), c.dimensions(), features, params);
    }

    private static String resolvePlinthBlock(LlmPlan plan, String corpus) {
        StyleAttributes sa = plan.styleAttributes();
        if (sa != null) {
            String fromPalette = sa.resolvePaletteBlock(
                    "plinth", "base", "foundation_band", "foundation", "base_material");
            if (fromPalette != null && !fromPalette.isBlank()) {
                return fromPalette;
            }
        }
        if (containsAny(corpus, List.of("深板", "深板岩", "deepslate"))) {
            return "minecraft:deepslate_bricks";
        }
        return null;
    }

    private static String resolveUpperWall(LlmPlan plan) {
        StyleAttributes sa = plan.styleAttributes();
        if (sa == null) return null;
        String fromPalette = sa.resolvePaletteBlock("wall_upper", "wall", "structure");
        if (fromPalette != null && !fromPalette.isBlank()) {
            return fromPalette;
        }
        if (sa.wallMaterial() != null && !sa.wallMaterial().isBlank()) {
            return sa.wallMaterial();
        }
        return null;
    }

    private static String resolveRoofBlock(LlmPlan plan) {
        StyleAttributes sa = plan.styleAttributes();
        if (sa == null) return null;
        String fromPalette = sa.resolvePaletteBlock("roof");
        if (fromPalette != null && !fromPalette.isBlank()) {
            return fromPalette;
        }
        if (sa.roofMaterial() != null && !sa.roofMaterial().isBlank()) {
            return sa.roofMaterial();
        }
        return null;
    }

    private static String requestCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent_text");
            if (ri != null) sb.append(ri).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> m && m.get("original_text") != null) {
                sb.append(m.get("original_text")).append(' ');
            }
        }
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
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
