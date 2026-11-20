package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Residential / baroque / mansion prompts must not stay locked on {@code stadium_bowl}
 * (Bird's Nest mesh) when the LLM invents 「椭圆」/「拱」without a real stadium ask.
 * <p>
 * Strip alone is not enough: leftover {@code ribbon_glazing}, {@code Modern_Stadium_*} style,
 * and {@code RADIAL_RING} layout still emit a glass curtain-wall box without a mansard roof.
 * Demotion and mansard rewrite are host-scoped to the stadium-poisoned (or sole style-poisoned
 * primary) mass — never every STRUCTURE / every ROOF in a compound.
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

    /** Bowl/mesh/field params that are stadium-executor evidence by themselves. */
    private static final List<String> STADIUM_EXCLUSIVE_PARAM_KEYS = List.of(
            "meshStructure", "mesh_structure", "bowlSteepness", "bowl_steepness",
            "field_ratio", "enclosure_coverage", "tierStep", "tier_step", "field_to_bowl");

    /**
     * Ordinary building params also written by Python mass defaults — never stadium evidence alone.
     * Cleaned only after a component already hit a true stadium route marker.
     */
    private static final List<String> STADIUM_COMPAT_PARAM_KEYS = List.of(
            "void_ratio", "tier_count", "elliptical");

    private record StripOutcome(Component component, boolean stadiumRouteDemoted) {}

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

        boolean stylePoisoned = styleLooksStadium(plan);
        // Stable target identity: stadium-marker host first; else sole style-poisoned primary.
        String demotePrimaryId = resolveDemotePrimaryId(plan, stylePoisoned);
        List<Component> masses = collectMasses(plan.components());

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        Set<String> demotedHosts = new HashSet<>();
        Component primaryDemotedMass = null;
        for (Component c : plan.components()) {
            StripOutcome outcome = stripForcedStadium(plan, c, requestCorpus, demotePrimaryId);
            if (outcome.component() != c) {
                changed = true;
            }
            // Only true stadium-route demotion enters demotedHosts — format cleanup alone does not.
            if (outcome.stadiumRouteDemoted() && isDemotableHost(outcome.component())) {
                String id = componentId(outcome.component());
                if (!id.isBlank()) {
                    demotedHosts.add(id);
                }
                String priorId = componentId(c);
                if (!priorId.isBlank()) {
                    demotedHosts.add(priorId);
                }
                if (primaryDemotedMass == null
                        && (id.equals(demotePrimaryId) || priorId.equals(demotePrimaryId)
                        || looksPrimaryMass(outcome.component()))) {
                    primaryDemotedMass = outcome.component();
                }
            }
            out.add(outcome.component());
        }
        if (primaryDemotedMass == null && demotePrimaryId != null) {
            for (Component c : out) {
                if (demotePrimaryId.equals(componentId(c))) {
                    primaryDemotedMass = c;
                    break;
                }
            }
        }

        boolean wantsMansard = containsAny(requestCorpus,
                List.of("马萨", "mansard", "折线", "老虎窗", "dormer", "巴洛克", "baroque"));
        Set<String> roofsCoveringDemoted = new HashSet<>();
        if (wantsMansard && !demotedHosts.isEmpty()) {
            List<Component> roofFixed = new ArrayList<>(out.size());
            for (Component c : out) {
                if (c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")
                        && roofBelongsToDemotedHost(c, demotedHosts, primaryDemotedMass, masses)) {
                    Component next = ensureMansardOnRoof(plan, c, requestCorpus);
                    if (next != c) {
                        changed = true;
                    }
                    String host = hostIdOf(c);
                    if (host != null) {
                        roofsCoveringDemoted.add(host);
                    } else if (primaryDemotedMass != null) {
                        roofsCoveringDemoted.add(componentId(primaryDemotedMass));
                    }
                    roofFixed.add(next);
                } else {
                    roofFixed.add(c);
                }
            }
            out = roofFixed;
        }
        if (wantsMansard && primaryDemotedMass != null
                && demotedHosts.contains(componentId(primaryDemotedMass))) {
            String primaryId = componentId(primaryDemotedMass);
            boolean covered = (!primaryId.isBlank() && roofsCoveringDemoted.contains(primaryId));
            if (!covered) {
                out.add(syntheticMansardRoof(primaryDemotedMass, requestCorpus));
                changed = true;
            }
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
                        + "(style={}, mansard={}, clear ribbon_glazing, hosts={})",
                style, wantsMansard, demotedHosts);
        return new LlmPlan(
                plan.mode(), style, plan.anchor(), constraints, layout,
                out, plan.genome(), plan.styleAttributes(), hints,
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    /**
     * Prefer a host that already carries stadium typology/params; otherwise the first
     * MASS_MAIN / STRUCTURE when style alone is poisoned. Never "every STRUCTURE".
     */
    private static String resolveDemotePrimaryId(LlmPlan plan, boolean stylePoisoned) {
        Component poisoned = null;
        Component firstPrimary = null;
        for (Component c : plan.components()) {
            if (c == null || !isDemotableHost(c)) continue;
            if (carriesStadiumRouteMarkers(c)) {
                poisoned = c;
                break;
            }
            if (firstPrimary == null && looksPrimaryMass(c)) {
                firstPrimary = c;
            }
        }
        Component target = poisoned != null ? poisoned : (stylePoisoned ? firstPrimary : null);
        return target == null ? null : componentId(target);
    }

    private static List<Component> collectMasses(List<Component> components) {
        List<Component> masses = new ArrayList<>();
        for (Component m : components) {
            if (m != null && ComponentPlanTypes.isMassType(
                    ComponentPlanTypes.normalizeType(m.componentType()))) {
                masses.add(m);
            }
        }
        return masses;
    }

    private static boolean isDemotableHost(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return t.startsWith("MASS") || "MAIN_MASS".equals(t) || "STRUCTURE".equals(t)
                || "TOWER".equals(t) || t.startsWith("TOWER");
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
            next.put("roof_specialty", "mansard");
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
                boolean wantsDormers = containsAny(corpus, List.of("老虎窗", "dormer", "mansard_dormer"));
                next.put("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
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
            if (carriesStadiumRouteMarkers(c)) {
                return true;
            }
        }
        if (plan.layout() != null && plan.layout().skeletonType() == Layout.SkeletonType.RADIAL_RING
                && styleLooksStadium(plan)) {
            return true;
        }
        return styleLooksStadium(plan);
    }

    /**
     * True stadium routing evidence only — never plain material_hints, void_ratio, tier_count,
     * or elliptical alone (those are ordinary mass defaults from Python normalization).
     */
    private static boolean carriesStadiumRouteMarkers(Component c) {
        if (c == null) return false;
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
            for (String key : STADIUM_EXCLUSIVE_PARAM_KEYS) {
                if (c.params().containsKey(key)) {
                    return true;
                }
            }
            Object aspect = c.params().get("window_aspect");
            if (aspect == null) aspect = c.params().get("windowAspect");
            if (aspect != null && String.valueOf(aspect).toLowerCase(Locale.ROOT).contains("ribbon")) {
                return true;
            }
            Object assembly = c.params().get("assembly");
            if (assembly instanceof Map<?, ?> raw) {
                Object preset = raw.get("preset");
                if (preset == null) preset = raw.get("presetId");
                if (preset != null) {
                    String s = String.valueOf(preset).toLowerCase(Locale.ROOT);
                    if (s.contains("stadium") || s.contains("birds_nest")) {
                        return true;
                    }
                }
            }
            if (materialHintsLookStadium(c.params().get("material_hints"))) {
                return true;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("stadium_bowl") || lower.contains("birds_nest")
                        || lower.contains("typology:stadium") || lower.contains("ribbon_glazing")
                        || lower.contains("elliptical_footprint") || lower.contains("radial_ring")
                        || lower.contains("arch_ribs") || lower.contains("two_tier_enclosure")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean materialHintsLookStadium(Object materialHints) {
        if (!(materialHints instanceof Map<?, ?> map)) {
            return false;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String blob = (String.valueOf(e.getKey()) + " " + e.getValue()).toLowerCase(Locale.ROOT);
            if (blob.contains("stadium") || blob.contains("birds_nest") || blob.contains("bird_nest")
                    || blob.contains("ribbon") || blob.contains("bowl")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unbound roofs bind only via unique spatial MATCH to a demoted primary —
     * AMBIGUOUS / NONE leave the roof alone (multi-building safety).
     */
    private static boolean roofBelongsToDemotedHost(
            Component roof,
            Set<String> demotedHosts,
            Component primaryDemotedMass,
            List<Component> masses) {
        String host = hostIdOf(roof);
        if (host != null && !host.isBlank()) {
            return demotedHosts.contains(host);
        }
        if (primaryDemotedMass == null) {
            return false;
        }
        String primaryId = componentId(primaryDemotedMass);
        if (primaryId.isBlank() || !demotedHosts.contains(primaryId)) {
            return false;
        }
        return ComponentSatelliteInference.resolveUnboundHost(primaryDemotedMass, masses, roof)
                == ComponentSatelliteInference.HostPick.MATCH;
    }

    private static String hostIdOf(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        if (host == null || String.valueOf(host).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(host))) {
            return null;
        }
        return String.valueOf(host);
    }

    private static String componentId(Component c) {
        if (c == null || c.params() == null || c.params().get("component_id") == null) {
            return "";
        }
        return String.valueOf(c.params().get("component_id"));
    }

    /**
     * @param demotePrimaryId stable component_id of the single host allowed to demote from
     *                        STRUCTURE→MASS_MAIN when style alone is poisoned.
     */
    private static StripOutcome stripForcedStadium(
            LlmPlan plan, Component c, String requestCorpus, String demotePrimaryId) {
        if (c == null) {
            return new StripOutcome(c, false);
        }
        boolean stadiumHit = false;
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
                        stadiumHit = true;
                    }
                }
            }
            for (String key : List.of("module_id", "landmark", "reference_landmark")) {
                Object v = params.get(key);
                if (v != null) {
                    String s = String.valueOf(v).toLowerCase(Locale.ROOT);
                    if (s.contains("stadium") || s.contains("birds_nest") || s.contains("bird_nest")) {
                        params.remove(key);
                        stadiumHit = true;
                    }
                }
            }
            for (String key : STADIUM_EXCLUSIVE_PARAM_KEYS) {
                if (params.remove(key) != null) {
                    stadiumHit = true;
                }
            }
            for (String key : List.of("window_aspect", "windowAspect")) {
                Object v = params.get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("ribbon")) {
                    params.put(key, "arched");
                    stadiumHit = true;
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
                        stadiumHit = true;
                    }
                }
            }
            // material_hints: only strip when stadium-tainted — plain palette hints are not evidence.
            Object materialHints = params.get("material_hints");
            if (materialHintsLookStadium(materialHints)) {
                params.remove("material_hints");
                stadiumHit = true;
            }
        }
        if (features != null) {
            List<String> next = new ArrayList<>();
            boolean featHit = false;
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
                    featHit = true;
                    continue;
                }
                next.add(f);
            }
            if (featHit) {
                features = next;
                stadiumHit = true;
            }
        }
        String type = c.componentType();
        boolean allowStyleDemote = demotePrimaryId != null
                && !demotePrimaryId.isBlank()
                && demotePrimaryId.equals(componentId(c));
        boolean demoteStructure = type != null && "STRUCTURE".equalsIgnoreCase(type.trim())
                && (stadiumHit || allowStyleDemote);
        if (!stadiumHit && !demoteStructure) {
            return new StripOutcome(c, false);
        }
        if (demoteStructure) {
            type = "MASS_MAIN";
            stadiumHit = true;
        }
        if (params == null) {
            params = new HashMap<>();
        }
        // Generic mass defaults: strip only after true stadium hit, and only when unlocked.
        if (stadiumHit) {
            for (String key : STADIUM_COMPAT_PARAM_KEYS) {
                if (params.containsKey(key) && DesignProvenance.canMutateParam(plan, params, key)) {
                    params.remove(key);
                }
            }
        }
        boolean stadiumRouteDemoted = stadiumHit || demoteStructure;
        if (stadiumRouteDemoted) {
            params.put("stadium_misroute_demoted", true);
        }
        if (stadiumRouteDemoted && containsAny(requestCorpus,
                List.of("马萨", "mansard", "折线", "老虎窗", "dormer", "巴洛克", "baroque"))) {
            params.putIfAbsent("roof_type", "mansard");
            params.putIfAbsent("preferred_roof_type", "mansard");
            boolean wantsDormers = containsAny(requestCorpus, List.of("老虎窗", "dormer", "mansard_dormer"));
            params.putIfAbsent("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
            if (wantsDormers) {
                params.putIfAbsent("roof_dormers", true);
            } else {
                params.putIfAbsent("roof_dormers", false);
            }
        }
        if (stadiumRouteDemoted && containsAny(requestCorpus, List.of("拱形窗", "拱形", "arched", "石英"))) {
            params.putIfAbsent("window_aspect", "arched");
            params.putIfAbsent("window_style", "arched");
        }
        if (features == null) {
            features = new ArrayList<>();
        } else {
            features = new ArrayList<>(features);
        }
        if (stadiumRouteDemoted && containsAny(requestCorpus, List.of("巴洛克", "baroque"))
                && features.stream().noneMatch(f ->
                f != null && f.toLowerCase(Locale.ROOT).contains("baroque"))) {
            features.add("baroque");
        }
        if (stadiumRouteDemoted && containsAny(requestCorpus, List.of("马萨", "mansard", "折线"))
                && features.stream().noneMatch(f ->
                f != null && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
            features.add("mansard");
        }
        return new StripOutcome(
                new Component(type, c.slotId(), c.relativePosition(), c.dimensions(), features, params),
                stadiumRouteDemoted);
    }

    private static Component ensureMansardOnRoof(LlmPlan plan, Component c, String requestCorpus) {
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
        boolean roofIsMansard = rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard");
        if (!roofIsMansard) {
            // Locked non-mansard: leave the whole roof bundle alone (no specialty/dormer mix).
            if (!DesignProvenance.canMutateParam(plan, params, "roof_type")) {
                return c;
            }
            if (!DesignProvenance.putIfAllowed(plan, params, "roof_type", "mansard")) {
                return c;
            }
            roofIsMansard = true;
            changed = true;
        }
        if (roofIsMansard) {
            if (!params.containsKey("roof_specialty")
                    && DesignProvenance.putIfAllowed(plan, params, "roof_specialty", "mansard_dormer")) {
                changed = true;
            }
            if (!params.containsKey("roof_dormers") && !params.containsKey("dormers")
                    && DesignProvenance.putIfAllowed(plan, params, "roof_dormers", true)) {
                changed = true;
            }
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
