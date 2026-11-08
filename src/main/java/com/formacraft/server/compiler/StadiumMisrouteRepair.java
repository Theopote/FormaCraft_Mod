package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Residential / baroque / mansion prompts must not stay locked on {@code stadium_bowl}
 * (Bird's Nest mesh) when the LLM invents 「椭圆」/「拱」without a real stadium ask.
 * <p>
 * Strip alone is not enough: leftover {@code ribbon_glazing}, {@code Modern_Stadium_*} style,
 * and {@code RADIAL_RING} layout still emit a glass curtain-wall box without a mansard roof.
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

    private static final List<String> STADIUM_PARAM_KEYS = List.of(
            "elliptical", "meshStructure", "mesh_structure", "bowlSteepness", "bowl_steepness",
            "field_ratio", "void_ratio", "enclosure_coverage", "tierStep", "tier_step",
            "tier_count", "field_to_bowl");

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
        boolean hasRoof = false;
        Component primaryMass = null;
        for (Component c : plan.components()) {
            if (c != null && c.componentType() != null
                    && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")) {
                hasRoof = true;
            }
            Component repaired = stripForcedStadium(c, requestCorpus);
            if (repaired != c) {
                changed = true;
            }
            out.add(repaired);
            if (primaryMass == null && looksPrimaryMass(repaired)) {
                primaryMass = repaired;
            }
        }
        if (!hasRoof && primaryMass != null) {
            out.add(syntheticMansardRoof(primaryMass, requestCorpus));
            changed = true;
        } else if (hasRoof) {
            List<Component> roofFixed = new ArrayList<>(out.size());
            for (Component c : out) {
                Component next = ensureMansardOnRoof(c, requestCorpus);
                if (next != c) {
                    changed = true;
                }
                roofFixed.add(next);
            }
            out = roofFixed;
        }

        String style = plan.styleProfile();
        if (styleLooksStadium(plan)) {
            style = resolveResidentialStyle(requestCorpus);
            changed = true;
        }

        Layout layout = rewriteLayout(plan.layout());
        if (layout != plan.layout()) {
            changed = true;
        }

        GlobalConstraints constraints = rewriteConstraints(plan.globalConstraints());
        if (constraints != plan.globalConstraints()) {
            changed = true;
        }

        Map<String, Object> hints = rewriteProportionHints(plan.proportionHints(), requestCorpus);
        if (hints != plan.proportionHints()) {
            changed = true;
        }

        if (!changed) {
            return plan;
        }
        FormacraftMod.LOGGER.info(
                "StadiumMisrouteRepair: demoted stadium_bowl → residential/baroque mansion "
                        + "(style={}, mansard={}, clear ribbon_glazing)",
                style, containsAny(requestCorpus, List.of("马萨", "mansard", "折线", "老虎窗", "dormer")));
        return new LlmPlan(
                plan.mode(), style, plan.anchor(), constraints, layout,
                out, plan.genome(), plan.styleAttributes(), hints,
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    private static boolean looksPrimaryMass(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t) || "STRUCTURE".equals(t);
    }

    private static String resolveResidentialStyle(String corpus) {
        if (containsAny(corpus, List.of("巴洛克", "baroque", "马萨", "mansard"))) {
            return "Legacy_Classical_Facade";
        }
        return "DEFAULT";
    }

    private static Layout rewriteLayout(Layout layout) {
        if (layout == null || layout.skeletonType() == null) {
            return layout;
        }
        if (layout.skeletonType() != Layout.SkeletonType.RADIAL_RING) {
            return layout;
        }
        return new Layout(Layout.SkeletonType.COMPOUND, false, layout.slots());
    }

    private static GlobalConstraints rewriteConstraints(GlobalConstraints gc) {
        if (gc == null || gc.symmetry() != GlobalConstraints.Symmetry.RADIAL) {
            return gc;
        }
        return new GlobalConstraints(
                gc.facing(),
                GlobalConstraints.Symmetry.MIRROR_X,
                gc.terrainStrategy(),
                gc.useBuildingMass());
    }

    private static Map<String, Object> rewriteProportionHints(Map<String, Object> hints, String corpus) {
        if (hints == null || hints.isEmpty()) {
            if (!containsAny(corpus, List.of("马萨", "mansard", "折线", "巴洛克", "baroque"))) {
                return hints;
            }
            Map<String, Object> next = new HashMap<>();
            next.put("typology", "baroque_mansion");
            next.put("roof_specialty", "mansard_dormer");
            return next;
        }
        boolean dirty = false;
        Map<String, Object> next = new HashMap<>(hints);
        Object typology = next.get("typology");
        if (typology != null) {
            String t = String.valueOf(typology).toLowerCase(Locale.ROOT);
            if (t.contains("stadium") || t.contains("birds_nest")) {
                next.put("typology", "baroque_mansion");
                dirty = true;
            }
        }
        if (containsAny(corpus, List.of("马萨", "mansard", "折线", "老虎窗", "dormer"))) {
            Object specialty = next.get("roof_specialty");
            if (specialty == null || String.valueOf(specialty).isBlank()
                    || String.valueOf(specialty).toLowerCase(Locale.ROOT).contains("stadium")) {
                next.put("roof_specialty", "mansard_dormer");
                dirty = true;
            }
        }
        for (String key : List.of(
                "bowl_depth_to_width", "height_to_major_axis", "field_to_bowl", "void_ratio")) {
            if (next.remove(key) != null) {
                dirty = true;
            }
        }
        Object opening = next.get("opening_grammar");
        if (opening instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> og = new HashMap<>((Map<String, Object>) raw);
            Object aspect = og.get("window_aspect");
            if (aspect != null && String.valueOf(aspect).toLowerCase(Locale.ROOT).contains("ribbon")) {
                og.put("window_aspect", List.of("arched", "vertical"));
                next.put("opening_grammar", og);
                dirty = true;
            }
        }
        return dirty ? next : hints;
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
                Object aspect = c.params().get("window_aspect");
                if (aspect == null) {
                    aspect = c.params().get("windowAspect");
                }
                if (aspect != null && String.valueOf(aspect).toLowerCase(Locale.ROOT).contains("ribbon")) {
                    return true;
                }
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("stadium_bowl") || lower.contains("birds_nest")
                            || lower.contains("typology:stadium") || lower.contains("ribbon_glazing")) {
                        return true;
                    }
                }
            }
        }
        if (plan.layout() != null && plan.layout().skeletonType() == Layout.SkeletonType.RADIAL_RING
                && styleLooksStadium(plan)) {
            return true;
        }
        return styleLooksStadium(plan);
    }

    private static Component stripForcedStadium(Component c, String requestCorpus) {
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
            for (String key : STADIUM_PARAM_KEYS) {
                if (params.remove(key) != null) {
                    hit = true;
                }
            }
            for (String key : List.of("window_aspect", "windowAspect")) {
                Object v = params.get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("ribbon")) {
                    params.put(key, "arched");
                    hit = true;
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
            Object materialHints = params.get("material_hints");
            if (materialHints instanceof Map<?, ?>) {
                // Keep user-ish palette bands but drop stadium crest/glazing aliases later via style.
                params.remove("material_hints");
                hit = true;
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
                        || lower.contains("birds_nest")
                        || lower.contains("elliptical_footprint")
                        || lower.contains("radial_ring")
                        || lower.contains("ribbon_glazing")
                        || lower.contains("arch_ribs")
                        || lower.contains("two_tier_enclosure")) {
                    hit = true;
                    continue;
                }
                next.add(f);
            }
            if (hit) {
                features = next;
            }
        }
        // Always demote STRUCTURE stadium carriers for residential asks, even if only style was poisoned.
        String type = c.componentType();
        boolean demoteStructure = type != null && "STRUCTURE".equalsIgnoreCase(type.trim());
        if (!hit && !demoteStructure) {
            return c;
        }
        if (demoteStructure) {
            type = "MASS_MAIN";
            hit = true;
        }
        if (params == null) {
            params = new HashMap<>();
        }
        params.put("stadium_misroute_demoted", true);
        if (containsAny(requestCorpus, List.of("马萨", "mansard", "折线", "老虎窗", "dormer", "巴洛克", "baroque"))) {
            params.putIfAbsent("roof_type", "mansard");
            params.putIfAbsent("preferred_roof_type", "mansard");
            params.putIfAbsent("roof_specialty", "mansard_dormer");
            params.putIfAbsent("roof_dormers", true);
        }
        if (containsAny(requestCorpus, List.of("拱形窗", "拱形", "arched", "石英"))) {
            params.putIfAbsent("window_aspect", "arched");
            params.putIfAbsent("window_style", "arched");
        }
        if (features == null) {
            features = new ArrayList<>();
        } else {
            features = new ArrayList<>(features);
        }
        if (containsAny(requestCorpus, List.of("巴洛克", "baroque")) && features.stream().noneMatch(f ->
                f != null && f.toLowerCase(Locale.ROOT).contains("baroque"))) {
            features.add("baroque");
        }
        if (containsAny(requestCorpus, List.of("马萨", "mansard", "折线")) && features.stream().noneMatch(f ->
                f != null && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
            features.add("mansard");
        }
        return new Component(
                type, c.slotId(), c.relativePosition(), c.dimensions(), features, params);
    }

    private static Component ensureMansardOnRoof(Component c, String requestCorpus) {
        if (c == null || c.componentType() == null
                || !c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")) {
            return c;
        }
        if (!containsAny(requestCorpus, List.of("马萨", "mansard", "折线", "老虎窗", "dormer", "巴洛克", "baroque"))) {
            return c;
        }
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        boolean changed = false;
        Object rt = params.get("roof_type");
        if (rt == null) {
            rt = params.get("roofType");
        }
        if (rt == null || !String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
            params.put("roof_type", "mansard");
            changed = true;
        }
        if (!params.containsKey("roof_specialty")) {
            params.put("roof_specialty", "mansard_dormer");
            changed = true;
        }
        if (!params.containsKey("roof_dormers") && !params.containsKey("dormers")) {
            params.put("roof_dormers", true);
            changed = true;
        }
        if (!changed) {
            return c;
        }
        return new Component(
                c.componentType(), c.slotId(), c.relativePosition(), c.dimensions(), c.features(), params);
    }

    private static Component syntheticMansardRoof(Component mass, String requestCorpus) {
        Dimensions dims = mass.dimensions();
        Vec3i rp = mass.relativePosition() == null ? new Vec3i(0, 0, 0) : mass.relativePosition();
        int w = dims == null ? 12 : Math.max(4, dims.width());
        int d = dims == null ? 12 : Math.max(4, dims.depth());
        int h = dims == null ? 10 : Math.max(6, dims.height());
        int roofH = Math.max(4, Math.min(10, Math.round(Math.min(w, d) * 0.35f)));
        Map<String, Object> params = new HashMap<>();
        if (mass.params() != null && mass.params().get("component_id") != null) {
            params.put("host_id", mass.params().get("component_id"));
        }
        params.put("roof_type", "mansard");
        params.put("roof_specialty", "mansard_dormer");
        params.put("roof_dormers", true);
        params.put("stadium_misroute_demoted", true);
        if (containsAny(requestCorpus, List.of("椭圆山花", "oval pediment", "老虎窗", "dormer"))) {
            params.put("dormer_pediment", "oval");
        }
        List<String> features = new ArrayList<>();
        features.add("mansard");
        features.add("mansard_dormer");
        return new Component(
                "ROOF",
                mass.slotId(),
                new Vec3i(rp.x(), rp.y() + h, rp.z()),
                new Dimensions(w, d, roofH),
                features,
                params);
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
