package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Residential / baroque / mansion prompts must not stay locked on {@code stadium_bowl}
 * (Bird's Nest mesh) when the LLM invents 「椭圆」/「拱」without a real stadium ask.
 */
final class StadiumMisrouteRepair {
    private StadiumMisrouteRepair() {}

    private static final List<String> STADIUM_CUES = List.of(
            "体育场", "体育馆", "球场", "看台", "鸟巢", "国家体育场",
            "stadium", "arena", "bowl seating", "birds nest", "bird's nest");

    private static final List<String> RESIDENTIAL_CUES = List.of(
            "巴洛克", "大宅", "宅邸", "府邸", "别墅", "住宅", "公馆", "庄园", "马萨", "折线屋顶",
            "mansard", "baroque", "mansion", "villa", "residence", "palace", "manor",
            "townhouse", "rowhouse", "联排");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        // User/request text only — never treat LLM style_profile=Modern_Stadium_* as a stadium ask.
        String requestCorpus = requestIntentCorpus(plan);
        if (containsAny(requestCorpus, STADIUM_CUES)) {
            return plan;
        }
        boolean residentialAsk = containsAny(requestCorpus, RESIDENTIAL_CUES);
        boolean poisonedStadium = corpusContainsStadiumTypology(plan) || styleLooksStadium(plan);
        if (!residentialAsk || !poisonedStadium) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = stripForcedStadium(c);
            if (repaired != c) {
                changed = true;
            }
            out.add(repaired);
        }
        if (!changed) {
            return plan;
        }
        FormacraftMod.LOGGER.info(
                "StadiumMisrouteRepair: stripped stadium_bowl typology for non-stadium residential/baroque intent");
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static boolean styleLooksStadium(LlmPlan plan) {
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        return style.contains("stadium") || style.contains("birds_nest") || style.contains("elliptical");
    }

    private static boolean corpusContainsStadiumTypology(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (c.params() != null) {
                for (String key : List.of(
                        "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology",
                        "module_id", "reference_landmark", "landmark")) {
                    Object v = c.params().get(key);
                    if (v != null) {
                        String s = String.valueOf(v).toLowerCase(Locale.ROOT);
                        if (s.contains("stadium") || s.contains("birds_nest") || s.contains("bird_nest")) {
                            return true;
                        }
                    }
                }
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("stadium_bowl") || lower.contains("birds_nest")
                            || lower.contains("typology:stadium")) {
                        return true;
                    }
                }
            }
        }
        return styleLooksStadium(plan);
    }

    private static Component stripForcedStadium(Component c) {
        if (c == null) {
            return c;
        }
        boolean hit = false;
        Map<String, Object> params = c.params() == null ? null : new HashMap<>(c.params());
        List<String> features = c.features() == null ? null : new ArrayList<>(c.features());
        if (params != null) {
            for (String key : List.of(
                    "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology")) {
                Object v = params.get(key);
                if (v != null) {
                    String s = String.valueOf(v).toLowerCase(Locale.ROOT);
                    if (s.contains("stadium") || s.contains("birds_nest")) {
                        params.remove(key);
                        hit = true;
                    }
                }
            }
            for (String key : List.of("module_id", "landmark", "reference_landmark")) {
                Object v = params.get(key);
                if (v != null) {
                    String s = String.valueOf(v).toLowerCase(Locale.ROOT);
                    if (s.contains("stadium") || s.contains("birds_nest") || s.contains("bird_nest")) {
                        params.remove(key);
                        hit = true;
                    }
                }
            }
            Object assembly = params.get("assembly");
            if (assembly instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> asm = new HashMap<>((Map<String, Object>) raw);
                Object preset = asm.get("preset");
                if (preset == null) {
                    preset = asm.get("presetId");
                }
                if (preset != null) {
                    String s = String.valueOf(preset).toLowerCase(Locale.ROOT);
                    if (s.contains("stadium") || s.contains("birds_nest")) {
                        asm.remove("preset");
                        asm.remove("presetId");
                        params.put("assembly", asm);
                        hit = true;
                    }
                }
            }
        }
        if (features != null) {
            List<String> next = new ArrayList<>();
            for (String f : features) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("typology:stadium")
                        || lower.contains("stadium_bowl")
                        || lower.contains("landmark:birds_nest")
                        || lower.contains("module:birds_nest")
                        || lower.contains("birds_nest")) {
                    hit = true;
                    continue;
                }
                next.add(f);
            }
            if (hit) {
                features = next;
            }
        }
        if (!hit) {
            return c;
        }
        String type = c.componentType();
        if (type != null && "STRUCTURE".equalsIgnoreCase(type.trim())) {
            type = "MASS_MAIN";
            if (params == null) {
                params = new HashMap<>();
            }
            params.putIfAbsent("stadium_misroute_demoted", true);
        }
        return new Component(
                type, c.slotId(), c.relativePosition(), c.dimensions(), features, params);
    }

    /** Player request text only — excludes LLM style_profile / stadium feature noise. */
    private static String requestIntentCorpus(LlmPlan plan) {
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
