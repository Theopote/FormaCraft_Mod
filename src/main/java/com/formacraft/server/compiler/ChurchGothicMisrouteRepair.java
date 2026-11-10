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
 * Bare church / chapel prompts must not lock onto {@code gothic_cathedral_hall}.
 * Only keep the Gothic typology when the player (or plan) supplies Gothic cues.
 */
final class ChurchGothicMisrouteRepair {
    private ChurchGothicMisrouteRepair() {}

    // Style-only cues — never treat generic nave/aisle vocabulary as Gothic style intent.
    private static final List<String> GOTHIC_CUES = List.of(
            "gothic", "哥特", "pointed arch", "尖拱", "flying buttress", "飞扶壁",
            "rose window", "玫瑰花窗", "tracery", "花窗棂");

    private static final List<String> NON_GOTHIC_STYLE_CUES = List.of(
            "brutalist", "野兽派", "brutalism",
            "modern", "现代", "minimal", "极简",
            "baroque", "巴洛克",
            "romanesque", "罗马式",
            "byzantine", "拜占庭",
            "renaissance", "文艺复兴",
            "neoclassical", "新古典",
            "bauhaus", "包豪斯",
            "cyberpunk", "赛博");

    private static final List<String> CHURCH_CUES = List.of(
            "church", "cathedral", "chapel", "教堂", "大教堂", "礼拜", "天主堂");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = intentCorpus(plan);
        if (corpus.isBlank()) {
            return plan;
        }
        boolean churchLike = containsAny(corpus, CHURCH_CUES);
        boolean gothicCued = containsAny(corpus, GOTHIC_CUES);
        boolean nonGothicStyle = containsAny(corpus, NON_GOTHIC_STYLE_CUES);
        if (!churchLike) {
            return plan;
        }
        // Keep Gothic when explicitly requested, even if a modern adjective also appears.
        if (gothicCued && !nonGothicStyle) {
            return plan;
        }
        if (gothicCued && !looksForcedGothicTypology(plan)) {
            return plan;
        }
        // Strip gothic typology when style is non-Gothic, or when church is bare (no Gothic cues).
        if (!gothicCued || nonGothicStyle) {
            List<Component> out = new ArrayList<>(plan.components().size());
            boolean changed = false;
            for (Component c : plan.components()) {
                Component repaired = stripGothicTypology(c);
                if (repaired != c) {
                    changed = true;
                }
                out.add(repaired);
            }
            if (changed) {
                FormacraftMod.LOGGER.info(
                        "ChurchGothicMisrouteRepair: stripped gothic_cathedral_hall for non-Gothic church intent");
                return PlanComponentRewrite.withComponents(plan, out);
            }
        }
        return plan;
    }

    private static boolean looksForcedGothicTypology(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (c.params() != null) {
                for (String key : List.of(
                        "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology")) {
                    Object v = c.params().get(key);
                    if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("gothic_cathedral")) {
                        return true;
                    }
                }
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && f.toLowerCase(Locale.ROOT).contains("gothic_cathedral")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Component stripGothicTypology(Component c) {
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
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("gothic_cathedral")) {
                    params.remove(key);
                    hit = true;
                }
            }
            Object ref = params.get("reference_landmark");
            if (ref == null) ref = params.get("referenceLandmark");
            if (ref != null && String.valueOf(ref).toLowerCase(Locale.ROOT).contains("gothic_cathedral")) {
                params.remove("reference_landmark");
                params.remove("referenceLandmark");
                hit = true;
            }
        }
        if (features != null) {
            List<String> kept = new ArrayList<>(features.size());
            for (String f : features) {
                if (f != null && f.toLowerCase(Locale.ROOT).contains("gothic_cathedral")) {
                    hit = true;
                    continue;
                }
                kept.add(f);
            }
            features = kept;
        }
        if (!hit) {
            return c;
        }
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), features, params);
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
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> map) {
                Object text = map.get("original_text");
                if (text != null) sb.append(text).append(' ');
            }
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (c.features() != null) {
                    for (String f : c.features()) {
                        if (f != null) sb.append(f).append(' ');
                    }
                }
                if (c.params() != null && c.params().get("component_id") != null) {
                    sb.append(c.params().get("component_id")).append(' ');
                }
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
