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

    private static final List<String> LANTERN_CUES = List.of(
            "灯笼", "角灯", "挂灯", "lantern", "corner lantern", "corner_lantern");

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
        boolean wantsLanterns = wantsLanterns(corpus);

        // Pagoda cue + house-primary inject target, without an authored pagoda composition or
        // dense_eaves executor → capability_gap. Never inject + wipe satellites.
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
                if (!canStripDenseEaves(plan, c)) {
                    FormacraftMod.LOGGER.info(
                            "DenseEavesPagodaMisrouteRepair: locked dense_eaves on {} under non-pagoda intent → gap",
                            idOf(c));
                    return PlanComponentRewrite.withCapabilityGap(plan, new CapabilityGap(
                            "E_DENSE_EAVES_PAGODA_ROUTE_UNSUPPORTED",
                            "非密檐楼阁意图与设计锁定的 dense_eaves_pagoda 执行器冲突，已拒绝静默剥离 typology；请解除锁定或改用开放楼阁组合",
                            "DenseEavesPagodaMisrouteRepair",
                            List.of(
                                    "Clear design_sources lock on typology_id / footprint",
                                    "Or keep dense_eaves when pagoda geometry is intended")));
                }
                next = stripDenseEaves(plan, c);
                if (next != c) changed = true;
            } else if (wantsSquare && isSquareTowerTarget(c)) {
                Component squared = forceSquareFootprint(plan, c, corpus, wantsLanterns);
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
     * Gap only when a house-primary would have been injected AND no authored pagoda mass
     * already exists in the plan (hall + independent pagoda composition must compile).
     */
    private static boolean looksOpenPagodaMisroute(LlmPlan plan) {
        if (hasAuthoredPagodaComposition(plan)) {
            return false;
        }
        for (Component c : plan.components()) {
            if (c != null && looksHousePrimary(c)) {
                return true;
            }
        }
        return false;
    }

    /** Independent pagoda/stupa/tower mass already composed (not a gable house). */
    private static boolean hasAuthoredPagodaComposition(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (looksAuthoredPagodaMass(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksAuthoredPagodaMass(Component c) {
        if (c == null || c.componentType() == null) return false;
        if (carriesDenseEaves(c)) return true;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        if (!(t.startsWith("MASS") || "TOWER".equals(t) || t.startsWith("TOWER") || "STRUCTURE".equals(t))) {
            return false;
        }
        String id = idOf(c).toLowerCase(Locale.ROOT);
        String feat = featuresOf(c).toLowerCase(Locale.ROOT);
        boolean pagodaIdentity = id.contains("pagoda") || id.contains("stupa") || id.contains("宝塔")
                || id.contains("密檐") || id.contains("佛塔") || id.contains("舍利")
                || feat.contains("pagoda") || feat.contains("stupa") || feat.contains("宝塔")
                || feat.contains("密檐") || feat.contains("佛塔") || feat.contains("harmika")
                || feat.contains("bumpa");
        if (!pagodaIdentity) return false;
        // House-shaped MASS_MAIN with blank/gable roof is the inject target, not authored pagoda.
        return !looksHousePrimary(c);
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
        String id = idOf(c).toLowerCase(Locale.ROOT);
        String feat = featuresOf(c).toLowerCase(Locale.ROOT);
        if (id.contains("pagoda") || id.contains("stupa") || id.contains("宝塔") || id.contains("密檐")
                || feat.contains("pagoda") || feat.contains("stupa") || feat.contains("宝塔")) {
            return false;
        }
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

    private static boolean canStripDenseEaves(LlmPlan plan, Component c) {
        Map<String, Object> params = c.params();
        if (params == null) return true;
        for (String key : List.of(
                "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology",
                "footprint")) {
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).toLowerCase(Locale.ROOT);
            if ((key.contains("typology") || "typology".equals(key)) && s.contains("dense_eaves")
                    && !DesignProvenance.canMutateParam(plan, params, key)) {
                return false;
            }
            if ("footprint".equals(key) && !DesignProvenance.canMutateParam(plan, params, "footprint")) {
                return false;
            }
        }
        return true;
    }

    private static Component stripDenseEaves(LlmPlan plan, Component c) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        for (String key : List.of(
                "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology")) {
            Object v = params.get(key);
            if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("dense_eaves")
                    && DesignProvenance.canMutateParam(plan, params, key)) {
                params.remove(key);
            }
        }
        if (DesignProvenance.canMutateParam(plan, params, "footprint")) {
            params.remove("footprint");
        }
        List<String> nextFeat = new ArrayList<>();
        boolean canStripFeatures = DesignProvenance.canEnrichFeatures(plan, params);
        for (String f : features) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            if (canStripFeatures && (lower.contains("dense_eaves") || lower.contains("typology:dense_eaves")
                    || lower.contains("finial_spire") && lower.contains("pagoda"))) {
                continue;
            }
            nextFeat.add(f);
        }
        String type = c.componentType();
        if (type != null && "STRUCTURE".equalsIgnoreCase(type.trim())) {
            type = "MASS_MAIN";
            params.putIfAbsent("pagoda_misroute_demoted", true);
            DesignProvenance.putIfAllowed(plan, params, "floor_count", 3);
            params.putIfAbsent("open", false);
        }
        return new Component(type, c.slotId(), c.relativePosition(), c.dimensions(), nextFeat, params);
    }

    private static Component forceSquareFootprint(
            LlmPlan plan, Component c, String corpus, boolean wantsLanterns) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        boolean changed = false;
        Object fp = params.get("footprint");
        if (fp == null || !"square".equalsIgnoreCase(String.valueOf(fp))) {
            if (DesignProvenance.putIfAllowed(plan, params, "footprint", "square")) {
                changed = true;
            }
        }
        Object niche = params.get("niche_rhythm");
        if (niche != null && String.valueOf(niche).toLowerCase(Locale.ROOT).contains("octagon")
                && DesignProvenance.putIfAllowed(plan, params, "niche_rhythm", "none")) {
            changed = true;
        }
        int floors = ComponentParamParsers.intParam(params, "floor_count", "floorCount", "levels");
        if (floors <= 0) {
            floors = parseFloorCount(corpus);
            if (floors > 0 && DesignProvenance.putIfAllowed(plan, params, "floor_count", floors)) {
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
        if (floorHeight < 6 && DesignProvenance.putIfAllowed(plan, params, "floor_height", 6)) {
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
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        boolean alreadyHasLantern = features.stream()
                .anyMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("lantern"));
        // Only keep/add lanterns when request or AI plan already asks — never invent for 四角塔 alone.
        if (wantsLanterns || alreadyHasLantern) {
            if (DesignProvenance.canMutateParam(plan, params, "corner_lanterns")) {
                if (params.putIfAbsent("lantern_hang", 2) == null) changed = true;
                if (params.putIfAbsent("corner_lanterns", true) == null) changed = true;
                if (params.putIfAbsent("lantern_block", "minecraft:lantern") == null) changed = true;
                if (params.putIfAbsent("lantern_chain_block", "minecraft:iron_bars") == null) changed = true;
            }
            if (wantsLanterns && !alreadyHasLantern && DesignProvenance.canEnrichFeatures(plan, params)) {
                features.add("corner_lanterns");
                changed = true;
            }
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

    private static boolean wantsLanterns(String corpus) {
        if (corpus == null || corpus.isBlank()) return false;
        // Negation beats lantern substring (不要灯笼 / 无灯笼).
        if (corpus.contains("不要灯笼") || corpus.contains("无灯笼") || corpus.contains("勿挂灯")
                || corpus.contains("no lantern") || corpus.contains("without lantern")
                || corpus.contains("no corner lantern")) {
            return false;
        }
        return containsAny(corpus, LANTERN_CUES);
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
