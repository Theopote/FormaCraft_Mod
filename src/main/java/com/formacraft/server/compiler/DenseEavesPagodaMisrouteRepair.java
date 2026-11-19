package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dense-eaves octagon pagoda is only for 密檐/宝塔/古塔. 楼阁 / 废土错落 / 非对称 composition
 * must not collapse into a single STRUCTURE+octagon. 四角/方塔 keep an existing dense-eaves
 * executor square — never rewrite every MASS in a compound, and never silently inject a
 * whole-building typology over an open AI composition (DESIGN_OVERRIDE).
 */
final class DenseEavesPagodaMisrouteRepair {
    private DenseEavesPagodaMisrouteRepair() {}

    private static final List<String> PAGODA_CUES = List.of(
            "密檐", "密檐塔", "宝塔", "古塔", "舍利塔", "佛塔", "法门寺", "大雁塔",
            "pagoda", "stupa", "dense_eaves");

    private static final List<String> PAVILION_NOT_PAGODA = List.of(
            "楼阁", "废土", "非对称", "错落", "悬空", "水幕",
            "asymmetric", "wasteland", "pavilion tower", "louge");

    // Bare「四角」matches 四角亭 / 四角木柱 — require tower cues, not pavilion posts.
    private static final List<String> SQUARE_FOOTPRINT_CUES = List.of(
            "方塔", "方形塔", "四角塔", "四角塔楼", "square tower", "four-corner tower", "four corner tower");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = intentCorpus(plan);
        if (corpus.isBlank()) {
            return plan;
        }
        boolean wantsSquare = containsAny(corpus, SQUARE_FOOTPRINT_CUES);
        boolean hasPagodaCue = containsAny(corpus, PAGODA_CUES);
        boolean pavilionNotPagoda = containsAny(corpus, PAVILION_NOT_PAGODA) && !hasPagodaCue;

        // Pagoda intent + open multi-mass composition without a STRUCTURE dense_eaves executor
        // → capability_gap (same contract as Siheyuan/Landmark). Never inject + wipe satellites.
        if (hasPagodaCue && !pavilionNotPagoda && !alreadyDenseEavesExecutor(plan)
                && looksOpenPagodaMisroute(plan)) {
            FormacraftMod.LOGGER.info(
                    "DenseEavesPagodaMisrouteRepair: refusing silent dense_eaves_pagoda inject ({} components)",
                    plan.components().size());
            return PlanComponentRewrite.withCapabilityGap(plan, new CapabilityGap(
                    "E_DENSE_EAVES_PAGODA_ROUTE_UNSUPPORTED",
                    "宝塔/密檐意图命中开放组合或房屋体量，已拒绝用内置 dense_eaves_pagoda 模板覆盖并删除其他建筑的屋顶/门窗；请保留开放组合或路由 STRUCTURE typology:dense_eaves_pagoda",
                    "DenseEavesPagodaMisrouteRepair",
                    List.of(
                            "Keep authored masses / roofs / openings and re-plan pagoda geometry",
                            "Or ensure STRUCTURE with typology_id=dense_eaves_pagoda",
                            "Do not silently delete compound ROOF/FACADE/ENTRANCE/CROWN")));
        }

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = c;
            if (pavilionNotPagoda && carriesDenseEaves(c)) {
                next = stripDenseEaves(c);
                changed = true;
            } else if (wantsSquare && isSquareTowerTarget(c)) {
                // Host-scoped: only the pagoda/tower executor — never annex MASS_SECONDARY houses.
                Component squared = forceSquareFootprint(plan, c, corpus);
                if (squared != c) {
                    next = squared;
                    changed = true;
                }
            }
            out.add(next);
        }
        if (!changed) {
            return plan;
        }
        if (pavilionNotPagoda) {
            FormacraftMod.LOGGER.info(
                    "DenseEavesPagodaMisrouteRepair: stripped dense_eaves_pagoda for non-pagoda 楼阁/composition intent");
        } else {
            FormacraftMod.LOGGER.info(
                    "DenseEavesPagodaMisrouteRepair: forced square footprint / storey height on tower host only");
        }
        return PlanComponentRewrite.withComponents(plan, out);
    }

    /** STRUCTURE (or TOWER) already carrying dense_eaves typology executor. */
    private static boolean alreadyDenseEavesExecutor(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!("STRUCTURE".equals(t) || "TOWER".equals(t) || t.startsWith("TOWER"))) continue;
            if (carriesDenseEaves(c)) return true;
        }
        return false;
    }

    /**
     * True only when the former inject path would have rewritten a house-primary mass
     * into dense_eaves_pagoda (and wiped satellites). Authored stupa/tower compositions
     * without a house-primary carrier must keep compiling — do not gap every multi-mass
     * plan that merely mentions 佛塔/宝塔.
     */
    private static boolean looksOpenPagodaMisroute(LlmPlan plan) {
        int housePrimaries = 0;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            if (looksHousePrimary(c)) housePrimaries++;
        }
        // No house-primary inject target → leave the authored composition alone.
        return housePrimaries >= 1;
    }

    /**
     * Square-tower force applies only to dense-eaves carriers or explicit tower/pagoda hosts —
     * not every MASS_MAIN/SECONDARY in the compound.
     */
    private static boolean isSquareTowerTarget(Component c) {
        if (c == null || c.componentType() == null) return false;
        if (carriesDenseEaves(c)) return true;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        if (!("STRUCTURE".equals(t) || "TOWER".equals(t) || t.startsWith("TOWER"))) {
            // MASS_* only when identity is clearly the tower (not an annex house).
            if (!t.startsWith("MASS")) return false;
        }
        String id = idOf(c).toLowerCase(Locale.ROOT);
        String feat = featuresOf(c).toLowerCase(Locale.ROOT);
        return id.contains("tower") || id.contains("pagoda") || id.contains("塔")
                || feat.contains("tower") || feat.contains("pagoda") || feat.contains("密檐")
                || feat.contains("four_corner") || feat.contains("四角塔");
    }

    private static boolean looksHousePrimary(Component c) {
        if (c == null || c.componentType() == null || c.params() == null) return false;
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        if (!("MASS_MAIN".equals(type) || "MAIN_MASS".equals(type) || "STRUCTURE".equals(type))) {
            return false;
        }
        if (carriesDenseEaves(c)) return false;
        String roof = String.valueOf(c.params().getOrDefault("roof_type",
                c.params().getOrDefault("roofType", ""))).toLowerCase(Locale.ROOT);
        return roof.contains("gable") || roof.isBlank() || "default".equals(roof);
    }

    private static boolean carriesDenseEaves(Component c) {
        if (c == null) return false;
        if (c.params() != null) {
            for (String key : List.of(
                    "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology")) {
                Object v = c.params().get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("dense_eaves")) {
                    return true;
                }
            }
            Object fp = c.params().get("footprint");
            if (fp != null && "octagon".equalsIgnoreCase(String.valueOf(fp))
                    && c.features() != null
                    && c.features().stream().anyMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("pagoda"))) {
                return true;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && f.toLowerCase(Locale.ROOT).contains("dense_eaves")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Component stripDenseEaves(Component c) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        for (String key : List.of(
                "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology")) {
            Object v = params.get(key);
            if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("dense_eaves")) {
                params.remove(key);
            }
        }
        params.remove("footprint");
        List<String> nextFeat = new ArrayList<>();
        for (String f : features) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.contains("dense_eaves") || lower.contains("typology:dense_eaves")
                    || lower.contains("finial_spire") && lower.contains("pagoda")) {
                continue;
            }
            nextFeat.add(f);
        }
        String type = c.componentType();
        if (type != null && "STRUCTURE".equalsIgnoreCase(type.trim())) {
            type = "MASS_MAIN";
            params.putIfAbsent("pagoda_misroute_demoted", true);
            params.putIfAbsent("floor_count", 3);
            params.putIfAbsent("open", false);
        }
        return new Component(type, c.slotId(), c.relativePosition(), c.dimensions(), nextFeat, params);
    }

    private static Component forceSquareFootprint(LlmPlan plan, Component c, String corpus) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        boolean changed = false;
        Object fp = params.get("footprint");
        if (fp == null || !"square".equalsIgnoreCase(String.valueOf(fp))) {
            if (DesignProvenance.canMutateParam(plan, params, "footprint")) {
                params.put("footprint", "square");
                changed = true;
            }
        }
        Object niche = params.get("niche_rhythm");
        if (niche != null && String.valueOf(niche).toLowerCase(Locale.ROOT).contains("octagon")
                && DesignProvenance.canMutateParam(plan, params, "niche_rhythm")) {
            params.put("niche_rhythm", "none");
            changed = true;
        }
        int floors = ComponentParamParsers.intParam(params, "floor_count", "floorCount", "levels");
        if (floors <= 0) {
            floors = parseFloorCount(corpus);
            if (floors > 0 && DesignProvenance.canMutateParam(plan, params, "floor_count")) {
                params.put("floor_count", floors);
                params.put("levels", floors);
                changed = true;
            }
        }
        if (floors <= 0) {
            floors = 3;
        }
        Dimensions dims = c.dimensions();
        if (dims != null && DesignProvenance.canMutateParam(plan, params, "height")) {
            int minHeight = Math.max(dims.height(), floors * 8 + 10);
            if (minHeight > dims.height()) {
                dims = new Dimensions(dims.width(), dims.depth(), minHeight);
                changed = true;
            }
        }
        int floorHeight = ComponentParamParsers.intParam(params, "floor_height", "floorHeight", "storey_height");
        if (floorHeight < 6 && DesignProvenance.canMutateParam(plan, params, "floor_height")) {
            params.put("floor_height", 6);
            params.put("storey_height", 6);
            changed = true;
        }
        if (params.get("tier_heights") instanceof List<?> tiers && !tiers.isEmpty()
                && DesignProvenance.canMutateParam(plan, params, "tier_heights")) {
            List<Object> nextTiers = new ArrayList<>(tiers.size());
            boolean tierChanged = false;
            for (Object t : tiers) {
                int v = 0;
                if (t instanceof Number n) v = n.intValue();
                else {
                    try { v = Integer.parseInt(String.valueOf(t)); } catch (NumberFormatException ignored) { v = 0; }
                }
                if (v > 0 && v < 6) {
                    nextTiers.add(6);
                    tierChanged = true;
                } else {
                    nextTiers.add(t);
                }
            }
            if (tierChanged) {
                params.put("tier_heights", nextTiers);
                changed = true;
            }
        }
        if (DesignProvenance.canMutateParam(plan, params, "corner_lanterns")) {
            if (params.putIfAbsent("lantern_hang", 2) == null) changed = true;
            if (params.putIfAbsent("corner_lanterns", true) == null) changed = true;
            if (params.putIfAbsent("lantern_block", "minecraft:lantern") == null) changed = true;
            if (params.putIfAbsent("lantern_chain_block", "minecraft:iron_bars") == null) changed = true;
        }
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("lantern"))) {
            features.add("corner_lanterns");
            changed = true;
        }
        if (!changed) return c;
        return new Component(c.componentType(), c.slotId(), c.relativePosition(), dims, features, params);
    }

    private static int parseFloorCount(String corpus) {
        if (corpus == null || corpus.isBlank()) return 0;
        if (corpus.contains("三层") || corpus.contains("3层") || corpus.contains("三層")) return 3;
        if (corpus.contains("四层") || corpus.contains("4层")) return 4;
        if (corpus.contains("五层") || corpus.contains("5层")) return 5;
        if (corpus.contains("七层") || corpus.contains("7层")) return 7;
        return 0;
    }

    private static String idOf(Component c) {
        if (c.params() == null || c.params().get("component_id") == null) return "";
        return String.valueOf(c.params().get("component_id"));
    }

    private static String featuresOf(Component c) {
        if (c.features() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String f : c.features()) {
            if (f != null) sb.append(f).append(' ');
        }
        return sb.toString();
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent_text");
            if (ri != null) sb.append(ri).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> m && m.get("original_text") != null) {
                sb.append(m.get("original_text")).append(' ');
            }
        }
        if (plan.playerFidelityNoticeZh() != null) {
            sb.append(plan.playerFidelityNoticeZh()).append(' ');
        }
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
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
