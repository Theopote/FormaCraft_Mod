package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
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
 * must not collapse into a single STRUCTURE+octagon. 四角/方塔 keep the typology but force
 * footprint=square and a usable storey height.
 */
final class DenseEavesPagodaMisrouteRepair {
    private DenseEavesPagodaMisrouteRepair() {}

    private static final List<String> PAGODA_CUES = List.of(
            "密檐", "密檐塔", "宝塔", "古塔", "舍利塔", "佛塔", "法门寺", "大雁塔",
            "pagoda", "stupa", "dense_eaves");

    private static final List<String> PAVILION_NOT_PAGODA = List.of(
            "楼阁", "废土", "非对称", "错落", "悬空", "水幕",
            "asymmetric", "wasteland", "pavilion tower", "louge");

    private static final List<String> SQUARE_FOOTPRINT_CUES = List.of(
            "四角", "方塔", "方形塔", "四角塔", "方正", "square tower", "four-corner", "four corner");

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

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = c;
            if (pavilionNotPagoda && carriesDenseEaves(c)) {
                next = stripDenseEaves(c);
                changed = true;
            } else if (wantsSquare && carriesDenseEaves(c)) {
                Component squared = forceSquareFootprint(c, corpus);
                if (squared != c) {
                    next = squared;
                    changed = true;
                }
            } else if (wantsSquare && looksTowerMass(c)) {
                Component squared = forceSquareFootprint(c, corpus);
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
                    "DenseEavesPagodaMisrouteRepair: forced square footprint / storey height for 四角塔 intent");
        }
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static boolean looksTowerMass(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return "STRUCTURE".equals(t) || "TOWER".equals(t) || t.startsWith("MASS");
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

    private static Component forceSquareFootprint(Component c, String corpus) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        boolean changed = false;
        Object fp = params.get("footprint");
        if (fp == null || !"square".equalsIgnoreCase(String.valueOf(fp))) {
            params.put("footprint", "square");
            changed = true;
        }
        // Prefer square niche rhythm over octagon face rotation.
        Object niche = params.get("niche_rhythm");
        if (niche != null && String.valueOf(niche).toLowerCase(Locale.ROOT).contains("octagon")) {
            params.put("niche_rhythm", "none");
            changed = true;
        }
        int floors = ComponentParamParsers.intParam(params, "floor_count", "floorCount", "levels");
        if (floors <= 0) {
            floors = parseFloorCount(corpus);
            if (floors > 0) {
                params.put("floor_count", floors);
                params.put("levels", floors);
                changed = true;
            }
        }
        if (floors <= 0) {
            floors = 3;
        }
        Dimensions dims = c.dimensions();
        if (dims != null) {
            // ~5 clear blocks per storey + eaves/crown budget so 三层 does not look squat.
            int minHeight = Math.max(dims.height(), floors * 6 + 5);
            if (minHeight > dims.height()) {
                dims = new Dimensions(dims.width(), dims.depth(), minHeight);
                changed = true;
            }
        }
        if (!changed) {
            return c;
        }
        return new Component(c.componentType(), c.slotId(), c.relativePosition(), dims, c.features(), params);
    }

    private static int parseFloorCount(String corpus) {
        if (corpus == null || corpus.isBlank()) return 0;
        if (corpus.contains("三层") || corpus.contains("3层") || corpus.contains("三層")) return 3;
        if (corpus.contains("四层") || corpus.contains("4层")) return 4;
        if (corpus.contains("五层") || corpus.contains("5层")) return 5;
        if (corpus.contains("七层") || corpus.contains("7层")) return 7;
        return 0;
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
