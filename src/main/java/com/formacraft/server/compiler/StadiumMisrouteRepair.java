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

    /**
     * Bowl/field params that are stadium-executor evidence by themselves.
     * Never include meshStructure / tierStep — shared by 土楼 MODULE and 天坛 radial terrace.
     */
    private static final List<String> STADIUM_EXCLUSIVE_PARAM_KEYS = List.of(
            "bowlSteepness", "bowl_steepness", "field_ratio", "enclosure_coverage", "field_to_bowl");

    /**
     * Shared / ordinary params — never stadium evidence alone. Cleaned only after a component
     * already hit a true stadium route marker (typology/module/exclusive bowl keys).
     */
    private static final List<String> STADIUM_COMPAT_PARAM_KEYS = List.of(
            "void_ratio", "tier_count", "elliptical",
            "meshStructure", "mesh_structure", "tierStep", "tier_step");

    private static final List<String> MANSARD_CUES = List.of(
            "马萨", "mansard", "折线屋顶", "折线形屋顶", "折线形");

    private static final List<String> DORMER_CUES = List.of(
            "老虎窗", "dormer", "mansard_dormer");

    private static final List<String> FLAT_ROOF_CUES = List.of(
            "平屋顶", "平顶", "flat roof", "flat-roof", "flatroof");

    private record StripOutcome(Component component, boolean stadiumRouteDemoted) {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        // User/request text only — never treat LLM style_profile=Modern_Stadium_* as a stadium ask.
        String requestCorpus = requestIntentCorpus(plan);
        boolean residentialAsk = containsAny(requestCorpus, RESIDENTIAL_CUES);
        boolean affirmativeStadiumAsk = hasAffirmativeCue(requestCorpus, STADIUM_CUES);
        // Pure stadium ask (no residential host) → leave alone. Negated 「不要体育场」is not affirmative.
        // Mixed residential + stadium compounds continue so the mansion can demote while arena hosts stay.
        if (affirmativeStadiumAsk && !residentialAsk) {
            return plan;
        }
        boolean poisonedStadium = corpusContainsStadiumTypology(plan) || styleLooksStadium(plan);
        if (!residentialAsk || !poisonedStadium) {
            return plan;
        }

        // Only hosts with true stadium route markers may demote — never first STRUCTURE by list order.
        String demotePrimaryId = resolveDemotePrimaryId(plan);
        List<Component> masses = collectMasses(plan.components());
        boolean wantsMansard = wantsMansardRoof(requestCorpus);
        boolean wantsDormers = hasAffirmativeCue(requestCorpus, DORMER_CUES);

        // Preserve arena executors AND their satellites (facade ribbon, arch ribs, etc.).
        Set<String> preservedStadiumHosts = new HashSet<>();
        for (Component c : plan.components()) {
            if (shouldPreserveStadiumExecutor(c, affirmativeStadiumAsk, plan)) {
                String id = componentId(c);
                if (!id.isBlank()) {
                    preservedStadiumHosts.add(id);
                }
            }
        }

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        Set<String> demotedHosts = new HashSet<>();
        Component primaryDemotedMass = null;
        for (Component c : plan.components()) {
            if (shouldPreserveStadiumExecutor(c, affirmativeStadiumAsk, plan)
                    || belongsToPreservedStadiumHost(c, preservedStadiumHosts, plan.components())) {
                out.add(c);
                continue;
            }
            StripOutcome outcome = stripForcedStadium(plan, c, requestCorpus, wantsMansard, wantsDormers);
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

        Set<String> roofsCoveringDemoted = new HashSet<>();
        if (wantsMansard && !demotedHosts.isEmpty()) {
            List<Component> roofFixed = new ArrayList<>(out.size());
            for (Component c : out) {
                if (c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")
                        && roofBelongsToDemotedHost(c, demotedHosts, primaryDemotedMass, masses)) {
                    Component next = ensureMansardOnRoof(plan, c, wantsMansard, wantsDormers);
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
                out.add(syntheticMansardRoof(primaryDemotedMass, wantsDormers));
                changed = true;
            }
        }

        String style = plan.styleProfile();
        if (styleLooksStadium(plan)) {
            style = resolveResidentialStyle(requestCorpus);
            changed = true;
        }

        // Do not collapse RADIAL_RING when a true arena STRUCTURE/MODULE was preserved.
        boolean preservedStadium = !preservedStadiumHosts.isEmpty();
        Layout layout = plan.layout();
        GlobalConstraints constraints = plan.globalConstraints();
        if (!preservedStadium) {
            layout = rewriteLayout(plan.layout());
            if (layout != plan.layout()) {
                changed = true;
            }
            constraints = rewriteConstraints(plan.globalConstraints());
            if (constraints != plan.globalConstraints()) {
                changed = true;
            }
        }

        Map<String, Object> hints = rewriteProportionHints(
                plan.proportionHints(), requestCorpus, preservedStadium);
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
     * Only a host that already carries true stadium route markers may be demoted.
     * Style-only poison rewrites style/layout but never guesses STRUCTURE identity by list order.
     */
    private static String resolveDemotePrimaryId(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null || !isDemotableHost(c)) continue;
            if (carriesStadiumRouteMarkers(c)) {
                return componentId(c);
            }
        }
        return null;
    }

    /**
     * Mixed residential+stadium: preserve STRUCTURE/MODULE/TOWER arena executors by role — never by
     * "how many stadium markers exist". MASS_MAIN shells never stay as stadium.
     * <ul>
     *   <li>Clean residential host beside this stadium → preserve (sole real arena is valid).</li>
     *   <li>Several stadium-marked hosts → preserve only dedicated arena cues / bowl scale.</li>
     *   <li>Sole stadium-marked host under a residential ask → demote (classic misroute).</li>
     * </ul>
     */
    private static boolean shouldPreserveStadiumExecutor(
            Component c, boolean affirmativeStadiumAsk, LlmPlan plan) {
        if (!affirmativeStadiumAsk || c == null || plan == null || plan.components() == null) {
            return false;
        }
        if (!carriesStadiumRouteMarkers(c)) return false;
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        if ("MASS_MAIN".equals(t) || "MAIN_MASS".equals(t)) return false;
        if (!("STRUCTURE".equals(t) || "MODULE".equals(t) || "TOWER".equals(t) || t.startsWith("TOWER"))) {
            return false;
        }
        int stadiumHosts = countStadiumMarkedHosts(plan);
        boolean cleanResidentialBeside = hasSeparateNonStadiumPrimaryMass(plan, c);
        // "There is a residential MASS_MAIN" is not enough when multiple stadium-marked executors
        // exist — only dedicated arena cues / bowl scale identify the real arena (3-building mix).
        if (cleanResidentialBeside && stadiumHosts == 1) {
            return true;
        }
        if (stadiumHosts >= 2) {
            return looksDedicatedArenaHost(c);
        }
        return false;
    }

    /** Mass/STRUCTURE/MODULE hosts that can own stadium route markers (includes MODULE). */
    private static int countStadiumMarkedHosts(LlmPlan plan) {
        int n = 0;
        for (Component other : plan.components()) {
            if (other == null || !isStadiumCapableHost(other)) continue;
            if (carriesStadiumRouteMarkers(other)) n++;
        }
        return n;
    }

    private static boolean isStadiumCapableHost(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return t.startsWith("MASS") || "MAIN_MASS".equals(t) || "STRUCTURE".equals(t)
                || "MODULE".equals(t) || "TOWER".equals(t) || t.startsWith("TOWER");
    }

    /**
     * Satellites of a preserved arena via recursive host_id chain (decor → facade → arena).
     * Cycle-safe; never guesses by mansion name tags.
     */
    private static boolean belongsToPreservedStadiumHost(
            Component c, Set<String> preservedHosts, List<Component> all) {
        if (c == null || preservedHosts == null || preservedHosts.isEmpty()) {
            return false;
        }
        String id = componentId(c);
        if (!id.isBlank() && preservedHosts.contains(id)) {
            return true;
        }
        if (all == null || all.isEmpty()) {
            return false;
        }
        Map<String, String> parentById = new HashMap<>();
        for (Component other : all) {
            if (other == null) continue;
            String oid = componentId(other);
            if (oid.isBlank()) continue;
            String parent = hostIdOf(other);
            if (parent != null && !parent.isBlank()) {
                parentById.put(oid, parent);
            }
        }
        String cursor = hostIdOf(c);
        if (cursor == null || cursor.isBlank()) {
            cursor = id;
        }
        Set<String> seen = new HashSet<>();
        while (cursor != null && !cursor.isBlank() && seen.add(cursor)) {
            if (preservedHosts.contains(cursor)) {
                return true;
            }
            cursor = parentById.get(cursor);
        }
        return false;
    }

    /**
     * A clean primary residential mass (MASS_MAIN) without stadium markers means this stadium
     * executor is the real arena — not a misrouted shell. Wings alone do not qualify (avoids
     * preserving a misrouted STRUCTURE just because an annex exists).
     */
    private static boolean hasSeparateNonStadiumPrimaryMass(LlmPlan plan, Component stadium) {
        String stadiumId = componentId(stadium);
        for (Component other : plan.components()) {
            if (other == null || other == stadium || other.componentType() == null) continue;
            String t = other.componentType().trim().toUpperCase(Locale.ROOT);
            if (!("MASS_MAIN".equals(t) || "MAIN_MASS".equals(t))) continue;
            String otherId = componentId(other);
            if (!stadiumId.isBlank() && stadiumId.equals(otherId)) continue;
            if (carriesStadiumRouteMarkers(other)) continue;
            return true;
        }
        return false;
    }

    /** Arena role via slot/id cues or bowl-scale mass — not residential name guessing. */
    private static boolean looksDedicatedArenaHost(Component c) {
        String id = componentId(c).toLowerCase(Locale.ROOT);
        String slot = c.slotId() == null ? "" : c.slotId().trim().toLowerCase(Locale.ROOT);
        String blob = id + " " + slot;
        if (blob.contains("arena") || blob.contains("stadium") || blob.contains("bowl")
                || blob.contains("体育场") || blob.contains("球场") || blob.contains("看台")
                || blob.contains("鸟巢")) {
            return true;
        }
        Dimensions d = c.dimensions();
        return d != null && d.width() >= 30 && d.depth() >= 30;
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

    private static Map<String, Object> rewriteProportionHints(
            Map<String, Object> hints, String corpus, boolean preservedStadium) {
        boolean wantsMansard = wantsMansardRoof(corpus);
        boolean wantsDormers = hasAffirmativeCue(corpus, DORMER_CUES);
        boolean baroqueAsk = containsAny(corpus, List.of("巴洛克", "baroque"));
        if (hints == null || hints.isEmpty()) {
            if (preservedStadium || (!baroqueAsk && !wantsMansard)) {
                return hints;
            }
            Map<String, Object> next = new HashMap<>();
            if (baroqueAsk) {
                next.put("typology", "baroque_mansion");
            }
            // Never inject mansard specialty from baroque style alone or flat-roof asks.
            if (wantsMansard) {
                next.put("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
            }
            return next.isEmpty() ? hints : next;
        }
        boolean dirty = false;
        Map<String, Object> next = new HashMap<>(hints);
        Object typology = next.get("typology");
        if (typology != null && !preservedStadium) {
            String t = String.valueOf(typology).toLowerCase(Locale.ROOT);
            if (t.contains("stadium") || t.contains("birds_nest")) {
                next.put("typology", "baroque_mansion");
                dirty = true;
            }
        }
        if (wantsMansard && !preservedStadium) {
            Object specialty = next.get("roof_specialty");
            if (specialty == null || String.valueOf(specialty).isBlank()
                    || String.valueOf(specialty).toLowerCase(Locale.ROOT).contains("stadium")) {
                next.put("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
                dirty = true;
            }
        } else if (!wantsMansard) {
            Object specialty = next.get("roof_specialty");
            if (specialty != null) {
                String s = String.valueOf(specialty).toLowerCase(Locale.ROOT);
                if (s.contains("mansard") || s.contains("dormer")) {
                    next.remove("roof_specialty");
                    dirty = true;
                }
            }
        }
        // Keep bowl/field ratios when a real arena executor was preserved.
        if (!preservedStadium) {
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

    /** STRUCTURE demotes only when this component itself carries stadium route markers. */
    private static StripOutcome stripForcedStadium(
            LlmPlan plan, Component c, String requestCorpus, boolean wantsMansard, boolean wantsDormers) {
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
        // Never demote STRUCTURE from style-alone / list order — only true stadiumHit.
        String type = c.componentType();
        boolean demoteStructure = stadiumHit
                && type != null && "STRUCTURE".equalsIgnoreCase(type.trim());
        if (!stadiumHit) {
            return new StripOutcome(c, false);
        }
        if (demoteStructure) {
            type = "MASS_MAIN";
        }
        if (params == null) {
            params = new HashMap<>();
        }
        // Shared params (meshStructure/tierStep/void_ratio…): strip only after true stadium hit.
        for (String key : STADIUM_COMPAT_PARAM_KEYS) {
            if (params.containsKey(key) && DesignProvenance.canMutateParam(plan, params, key)) {
                params.remove(key);
            }
        }
        params.put("stadium_misroute_demoted", true);
        if (wantsMansard) {
            params.putIfAbsent("roof_type", "mansard");
            params.putIfAbsent("preferred_roof_type", "mansard");
            params.putIfAbsent("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
            params.putIfAbsent("roof_dormers", wantsDormers);
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
        if (containsAny(requestCorpus, List.of("巴洛克", "baroque"))
                && features.stream().noneMatch(f ->
                f != null && f.toLowerCase(Locale.ROOT).contains("baroque"))) {
            features.add("baroque");
        }
        if (wantsMansard && features.stream().noneMatch(f ->
                f != null && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
            features.add("mansard");
        }
        return new StripOutcome(
                new Component(type, c.slotId(), c.relativePosition(), c.dimensions(), features, params),
                true);
    }

    private static Component ensureMansardOnRoof(
            LlmPlan plan, Component c, boolean wantsMansard, boolean wantsDormers) {
        if (c == null || c.componentType() == null
                || !c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")) {
            return c;
        }
        if (!wantsMansard) {
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
            String specialty = wantsDormers ? "mansard_dormer" : "mansard";
            if (!params.containsKey("roof_specialty")
                    && DesignProvenance.putIfAllowed(plan, params, "roof_specialty", specialty)) {
                changed = true;
            }
            if (!params.containsKey("roof_dormers") && !params.containsKey("dormers")
                    && DesignProvenance.putIfAllowed(plan, params, "roof_dormers", wantsDormers)) {
                changed = true;
            }
        }
        if (!changed) {
            return c;
        }
        return new Component(
                c.componentType(), c.slotId(), c.relativePosition(), c.dimensions(), c.features(), params);
    }

    private static Component syntheticMansardRoof(Component mass, boolean wantsDormers) {
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
        params.put("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
        params.put("roof_dormers", wantsDormers);
        params.put("stadium_misroute_demoted", true);
        List<String> features = new ArrayList<>();
        features.add("mansard");
        if (wantsDormers) {
            features.add("mansard_dormer");
        }
        return new Component(
                "ROOF",
                mass.slotId(),
                new Vec3i(rp.x(), rp.y() + h, rp.z()),
                new Dimensions(w, d, roofH),
                features,
                params);
    }

    /** Explicit mansard ask only — baroque style alone does not authorize a mansard roof. */
    private static boolean wantsMansardRoof(String corpus) {
        if (corpus == null || corpus.isBlank()) return false;
        if (hasAffirmativeCue(corpus, FLAT_ROOF_CUES)) return false;
        return hasAffirmativeCue(corpus, MANSARD_CUES) || hasAffirmativeCue(corpus, DORMER_CUES);
    }

    /** True when at least one cue occurs outside local negation (不要做成X). */
    private static boolean hasAffirmativeCue(String corpus, List<String> cues) {
        if (corpus == null || corpus.isBlank() || cues == null) return false;
        for (String cue : cues) {
            if (cue == null || cue.isBlank()) continue;
            String needle = cue.toLowerCase(Locale.ROOT);
            int start = 0;
            while (true) {
                int idx = corpus.indexOf(needle, start);
                if (idx < 0) break;
                if (!RequestIntentInjector.isNegatedAt(corpus, idx)) {
                    return true;
                }
                start = idx + needle.length();
            }
        }
        return false;
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
