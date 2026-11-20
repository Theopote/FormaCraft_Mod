package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compile-time fidelity repair for baroque mansions.
 * <p>
 * Footprint, storey count, and dormer density stay LLM-authored. This repair only
 * corrects known execution failures (temple mis-label, full-footprint "dormer" plates,
 * unusable clear height, misplaced beam/spike decor) using structured plan signals
 * ({@code styleProfile}, typology hints, component features/params) — not fixed sizes
 * or exhaustive free-text keyword dictionaries.
 */
final class BaroqueMansionStyleRepair {
    private BaroqueMansionStyleRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = requestCorpus(plan);
        // Gate / gothic / temple companions must not abort baroque host repair for the whole compound.
        if (!looksBaroqueMansion(plan, corpus)) {
            return plan;
        }
        // Pure monument-gate / non-baroque style profiles without baroque markers stay untouched.
        if (styleProfileBlocksBaroque(plan) && !hasBaroqueMarkers(plan)
                && !(corpus.contains("baroque") || corpus.contains("巴洛克"))) {
            return plan;
        }

        boolean changed = false;
        String style = plan.styleProfile();
        if (styleLooksClassicalTemple(style)) {
            // GrecoRoman temple routing squashes mansard + arched openings — keep classical facade
            // without forcing a temple typology.
            style = "Legacy_Classical_Facade";
            changed = true;
        }

        boolean corpusWantsMansard = corpusWantsMansard(corpus);
        boolean corpusWantsDormers = hasAffirmativeCue(corpus, List.of("老虎窗", "dormer", "mansard_dormer"));
        String baroqueTarget = baroqueTargetHostId(plan, plan.components());
        boolean planHasPreservedStadium = planHasStadiumExecutor(plan);
        List<Component> out = new ArrayList<>(plan.components().size());
        for (Component c : plan.components()) {
            if (c == null) {
                continue;
            }
            boolean onBaroqueHost = belongsToBaroqueTarget(c, baroqueTarget, plan.components());
            // Geometric decor cleanup only on the confirmed baroque host — never stadium/gothic columns.
            if (onBaroqueHost) {
                Dimensions hostFootprint = hostMassFootprint(plan.components(), hostIdOf(c));
                if (isOversizedRoofDormerDecor(c, hostFootprint)) {
                    changed = true;
                    FormacraftMod.LOGGER.info(
                            "BaroqueMansionStyleRepair: drop oversized roof decor id={}",
                            c.params() == null ? c.componentType() : c.params().get("component_id"));
                    continue;
                }
                if (isMisplacedWallOrRoofDecor(c)) {
                    changed = true;
                    FormacraftMod.LOGGER.info(
                            "BaroqueMansionStyleRepair: drop misplaced decor id={}",
                            c.params() == null ? c.componentType() : c.params().get("component_id"));
                    continue;
                }
            }
            Component next = onBaroqueHost
                    ? enrich(plan, c, corpus, corpusWantsMansard, corpusWantsDormers, baroqueTarget)
                    : c;
            if (next != c) {
                changed = true;
            }
            out.add(next);
        }
        // Dormers only on roofs whose own host authorized them — never broadcast from a sibling mansard.
        List<Component> withRoof = new ArrayList<>(out.size());
        boolean roofTouched = false;
        for (Component c : out) {
            if (c != null && c.componentType() != null
                    && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")
                    && roofIsMansard(c)
                    && hostWantsDormers(plan, out, hostIdOf(c), corpusWantsDormers)) {
                Component ensured = ensureMansardDormers(plan, c);
                if (ensured != c) roofTouched = true;
                withRoof.add(ensured);
            } else {
                withRoof.add(c);
            }
        }
        if (roofTouched) {
            out = withRoof;
            changed = true;
        }

        Map<String, Object> hints = plan.proportionHints() == null
                ? new HashMap<>()
                : new HashMap<>(plan.proportionHints());
        Object typology = hints.get("typology");
        String typ = typology == null ? "" : String.valueOf(typology).toLowerCase(Locale.ROOT);
        // Never overwrite stadium typology when a real arena executor remains in the compound.
        if (!planHasPreservedStadium
                && (typ.contains("stadium") || typ.contains("temple") || typ.contains("pantheon"))) {
            hints.put("typology", "baroque_mansion");
            changed = true;
        }
        // Silhouette hint only — never global mansard_dormer (RoofGrammarResolver would broadcast).
        if (!hints.containsKey("roof_specialty") && corpusWantsMansard
                && baroqueTargetHasMansard(plan, out)) {
            hints.put("roof_specialty", "mansard");
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

    /**
     * Footprint for oversized-decor checks: resolve {@code hostId} as mass, or decor→roof→mass.
     * Never fall back to an unrelated primary mass — ambiguous ownership means keep the decor.
     */
    private static Dimensions hostMassFootprint(List<Component> components, String hostId) {
        if (hostId == null || hostId.isBlank() || components == null) {
            return null;
        }
        Dimensions direct = massFootprintById(components, hostId);
        if (direct != null) {
            return direct;
        }
        // host_id may point at a ROOF / satellite — walk one hop to its mass host.
        for (Component c : components) {
            if (c == null || !hostId.equals(componentId(c))) continue;
            String parent = hostIdOf(c);
            if (parent != null && !parent.isBlank()) {
                Dimensions viaParent = massFootprintById(components, parent);
                if (viaParent != null) {
                    return viaParent;
                }
            }
        }
        return null;
    }

    /** Exact component_id match only — floor plates must not impersonate the host via host_id. */
    private static Dimensions massFootprintById(List<Component> components, String id) {
        for (Component c : components) {
            if (c == null || c.dimensions() == null || c.componentType() == null) continue;
            if (isFloorPlate(c)) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!(type.startsWith("MASS") || "MAIN_MASS".equals(type) || "STRUCTURE".equals(type))) {
                continue;
            }
            if (id.equals(componentId(c))) {
                return c.dimensions();
            }
        }
        return null;
    }

    /**
     * Drop only clearly invalid attic spikes (DECOR). Authored BALCONY / TERRACE / DECK volumes
     * are design — never delete by skinny aspect ratio alone.
     */
    private static boolean isMisplacedWallOrRoofDecor(Component c) {
        if (c == null || c.componentType() == null || c.dimensions() == null) {
            return false;
        }
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        Dimensions d = c.dimensions();
        String id = c.params() == null ? ""
                : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
        if ("BALCONY".equals(type) || "TERRACE".equals(type) || "DECK".equals(type)) {
            return false;
        }
        if ("DECOR_DETAIL".equals(type) || "DECOR".equals(type)) {
            // Facade pilasters/columns are valid design — never delete by skinny aspect alone.
            // Only clear clearly attic/roof-spike deco (pediment/dormer rings on the roof plane).
            boolean atticCue = id.contains("pediment") || id.contains("dormer") || id.contains("attic")
                    || id.contains("山花") || id.contains("老虎窗");
            boolean roofSpike = d.height() >= Math.max(3, Math.max(d.width(), d.depth()))
                    && Math.min(d.width(), d.depth()) <= 3;
            return atticCue && roofSpike;
        }
        return false;
    }

    /** True when DECOR covers ~whole roof footprint as stacked edge rings (not a real dormer). */
    private static boolean isOversizedRoofDormerDecor(Component c, Dimensions primary) {
        if (c == null || c.componentType() == null || primary == null || c.dimensions() == null) {
            return false;
        }
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        if (!type.contains("DECOR")) return false;
        String id = c.params() == null ? ""
                : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
        boolean dormerCue = id.contains("dormer") || id.contains("pediment") || id.contains("老虎窗");
        if (!dormerCue && c.features() != null) {
            dormerCue = c.features().stream().anyMatch(f -> f != null && (
                    f.toLowerCase(Locale.ROOT).contains("dormer")
                            || f.toLowerCase(Locale.ROOT).contains("pediment")
                            || f.contains("老虎窗") || f.contains("山花")));
        }
        if (!dormerCue) return false;
        int w = c.dimensions().width();
        int d = c.dimensions().depth();
        // Nearly full host footprint → concentric edge rings, not nestled dormer shells.
        return w >= primary.width() - 2 && d >= primary.depth() - 2
                && w * 4 >= primary.width() * 3 && d * 4 >= primary.depth() * 3;
    }

    /**
     * Enable specialty dormers on mansard roofs only — never rewrite foreign gable/hip/xieshan hosts.
     * Honours design_sources locks and explicit roof_dormers=false.
     */
    private static Component ensureMansardDormers(LlmPlan plan, Component roof) {
        if (!roofIsMansard(roof)) {
            return roof;
        }
        Map<String, Object> params = roof.params() == null ? new HashMap<>() : new HashMap<>(roof.params());
        if (Boolean.FALSE.equals(params.get("roof_dormers"))
                && !DesignProvenance.canMutateParam(plan, params, "roof_dormers")) {
            return roof;
        }
        boolean changed = false;
        if (!Boolean.TRUE.equals(params.get("roof_dormers"))
                && DesignProvenance.putIfAllowed(plan, params, "roof_dormers", true)) {
            changed = true;
        }
        Object specialty = params.get("roof_specialty");
        if ((specialty == null || !String.valueOf(specialty).toLowerCase(Locale.ROOT).contains("dormer"))
                && DesignProvenance.putIfAllowed(plan, params, "roof_specialty", "mansard_dormer")) {
            changed = true;
        }
        if (!changed) return roof;
        return new Component(roof.componentType(), roof.slotId(), roof.relativePosition(),
                roof.dimensions(), roof.features(), params);
    }

    private static boolean roofIsMansard(Component roof) {
        if (roof == null || roof.params() == null) return false;
        Object rt = roof.params().get("roof_type");
        if (rt == null) rt = roof.params().get("roofType");
        return rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard");
    }

    private static boolean styleLooksClassicalTemple(String style) {
        if (style == null || style.isBlank()) return false;
        String s = style.toLowerCase(Locale.ROOT);
        return s.contains("grecoroman") || s.contains("greco_roman") || s.contains("stadium")
                || s.contains("pantheon");
    }

    /** Prefer styleProfile / typology / authored features over free-text synonym lists. */
    private static boolean looksBaroqueMansion(LlmPlan plan, String corpus) {
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        if (style.contains("baroque")) return true;
        if (plan.proportionHints() != null) {
            String typ = String.valueOf(plan.proportionHints().getOrDefault("typology", ""))
                    .toLowerCase(Locale.ROOT);
            if (typ.contains("baroque")) return true;
        }
        if (hasBaroqueMarkers(plan)) return true;
        // Minimal corpus fallback: only the style family name itself (LLM may echo it into intent).
        return corpus.contains("baroque") || corpus.contains("巴洛克");
    }

    private static boolean hasBaroqueMarkers(LlmPlan plan) {
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("baroque") || lower.contains("mansard") || f.contains("巴洛克")
                        || f.contains("马萨")) {
                    return true;
                }
            }
        }
        for (Component c : plan.components()) {
            if (c == null) continue;
            String id = c.params() == null ? ""
                    : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            if (id.contains("baroque") || id.contains("mansard")) return true;
            Object rt = c.params() == null ? null : c.params().get("roof_type");
            if (rt == null && c.params() != null) rt = c.params().get("roofType");
            if (rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")
                    && (id.contains("mansion") || styleProfileOrFeaturesSayBaroque(plan, c))) {
                return true;
            }
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

    private static boolean styleProfileOrFeaturesSayBaroque(LlmPlan plan, Component c) {
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        if (style.contains("baroque")) return true;
        if (c.features() == null) return false;
        return c.features().stream().anyMatch(f -> f != null
                && f.toLowerCase(Locale.ROOT).contains("baroque"));
    }

    /** Mansard authored on this host only — never “any mansard on the plan”. */
    private static boolean hostHasMansard(LlmPlan plan, List<Component> components, String hostId) {
        if (hostId == null || hostId.isBlank()) return false;
        List<Component> src = components != null ? components : plan.components();
        for (Component c : src) {
            if (c == null) continue;
            if (!hostOwnsComponent(c, hostId)) continue;
            if (c.params() != null) {
                Object rt = c.params().get("roof_type");
                if (rt == null) rt = c.params().get("roofType");
                if (rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
                    return true;
                }
            }
            if (c.features() != null && c.features().stream().anyMatch(f -> f != null
                    && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hostOwnsComponent(Component c, String hostId) {
        if (c == null || hostId == null || hostId.isBlank()) return false;
        if (hostId.equals(componentId(c))) return true;
        if (hostId.equals(hostIdOf(c))) return true;
        return false;
    }

    private static boolean baroqueTargetHasMansard(LlmPlan plan, List<Component> components) {
        String target = baroqueTargetHostId(plan, components);
        return !target.isBlank() && hostHasMansard(plan, components, target);
    }

    /**
     * Unique baroque-design host for corpus roof intents. Never “first MASS_MAIN by list order”.
     * Ambiguous multi-baroque compounds return blank → no cross-building roof force.
     */
    private static String baroqueTargetHostId(LlmPlan plan, List<Component> components) {
        List<Component> src = components != null ? components : plan.components();
        List<String> marked = new ArrayList<>();
        for (Component c : src) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!(type.startsWith("MASS") || "MAIN_MASS".equals(type) || "STRUCTURE".equals(type))) {
                continue;
            }
            if (isForeignStyleHost(c) || isFloorPlate(c)) continue;
            String id = componentId(c);
            if (id.isBlank()) continue;
            // Mansard roof form must NOT select baroque identity — French mansard annex ≠ baroque.
            if (hostLooksBaroque(plan, c)) {
                if (!marked.contains(id)) {
                    marked.add(id);
                }
            }
        }
        if (marked.size() == 1) {
            return marked.getFirst();
        }
        if (marked.size() > 1) {
            return "";
        }
        // Sole ordinary MASS_MAIN under baroque style/ask — only when unique.
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        String corpus = requestCorpus(plan);
        if (!style.contains("baroque")
                && !corpus.contains("baroque")
                && !corpus.contains("巴洛克")) {
            return "";
        }
        List<String> mains = new ArrayList<>();
        for (Component c : src) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!("MASS_MAIN".equals(type) || "MAIN_MASS".equals(type))) continue;
            if (isForeignStyleHost(c) || isFloorPlate(c)) continue;
            String id = componentId(c);
            if (!id.isBlank()) mains.add(id);
        }
        return mains.size() == 1 ? mains.getFirst() : "";
    }

    /** Style-family markers only — mansard roof form is not baroque identity. */
    private static boolean hostLooksBaroque(LlmPlan plan, Component c) {
        if (c == null) return false;
        String id = componentId(c).toLowerCase(Locale.ROOT);
        if (id.contains("baroque") || id.contains("巴洛克")) return true;
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && (f.toLowerCase(Locale.ROOT).contains("baroque") || f.contains("巴洛克"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isFloorPlate(Component c) {
        return c != null && isFloorPlateParams(c.params());
    }

    private static boolean isFloorPlateParams(Map<String, Object> params) {
        if (params == null) return false;
        Object extrude = params.get("extrude_mode");
        if (extrude == null) extrude = params.get("extrudeMode");
        String id = String.valueOf(params.getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
        return (extrude != null && String.valueOf(extrude).toLowerCase(Locale.ROOT).contains("plate"))
                || id.contains("floor_plate") || id.contains("floorplate") || id.endsWith("_plate");
    }

    /**
     * Dormers authorized for this host: own roof params/features, or corpus ask limited to the
     * unique baroque target — never copy from a sibling mansard's roof_dormers=true.
     */
    private static boolean hostWantsDormers(
            LlmPlan plan, List<Component> components, String hostId, boolean corpusWantsDormers) {
        if (hostId == null || hostId.isBlank()) return false;
        List<Component> src = components != null ? components : plan.components();
        for (Component c : src) {
            if (c == null || !hostOwnsComponent(c, hostId)) continue;
            if (c.params() != null) {
                if (Boolean.TRUE.equals(c.params().get("roof_dormers"))) return true;
                if (Boolean.FALSE.equals(c.params().get("roof_dormers"))) return false;
                Object specialty = c.params().get("roof_specialty");
                if (specialty != null && String.valueOf(specialty).toLowerCase(Locale.ROOT).contains("dormer")) {
                    return true;
                }
            }
            if (c.features() != null && c.features().stream().anyMatch(f -> f != null
                    && (f.toLowerCase(Locale.ROOT).contains("dormer") || f.contains("老虎窗")))) {
                return true;
            }
        }
        String target = baroqueTargetHostId(plan, src);
        return corpusWantsDormers && !target.isBlank() && hostId.equals(target);
    }

    /** Explicit mansard ask in request text — baroque style or sibling roofs are not enough. */
    private static boolean corpusWantsMansard(String corpus) {
        if (hasAffirmativeCue(corpus, List.of("平屋顶", "平顶", "flat roof", "flat-roof"))) {
            return false;
        }
        return hasAffirmativeCue(corpus, List.of("马萨", "mansard", "折线屋顶", "折线形屋顶", "折线形"));
    }

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

    /** Style profile alone is non-baroque (companions in corpus do not abort host-scoped repair). */
    private static boolean styleProfileBlocksBaroque(LlmPlan plan) {
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        return style.contains("victorian") || style.contains("gothic") || style.contains("tudor")
                || style.contains("craftsman") || style.contains("japanese")
                || style.contains("chinese") || style.contains("industrial")
                || style.contains("cyberpunk") || style.contains("art_deco")
                || style.contains("art-deco");
    }

    private static boolean isForeignStyleHost(Component c) {
        if (c == null) return false;
        String id = componentId(c).toLowerCase(Locale.ROOT);
        String slot = c.slotId() == null ? "" : c.slotId().toLowerCase(Locale.ROOT);
        String blob = id + " " + slot;
        if (blob.contains("gate") || blob.contains("arch") || blob.contains("triumphal")
                || blob.contains("门楼") || blob.contains("牌楼") || blob.contains("bell")
                || blob.contains("gothic") || blob.contains("钟楼") || blob.contains("哥特")
                || blob.contains("stadium") || blob.contains("arena") || blob.contains("bowl")
                || blob.contains("体育场") || blob.contains("球场") || blob.contains("看台")
                || blob.contains("tulou") || blob.contains("土楼") || blob.contains("temple")
                || blob.contains("寺庙")) {
            return true;
        }
        if (c.params() != null) {
            for (String key : List.of(
                    "typology_id", "structural_typology", "typologyId", "module_id", "typology")) {
                Object v = c.params().get(key);
                if (v == null) continue;
                String s = String.valueOf(v).toLowerCase(Locale.ROOT);
                if (s.contains("stadium") || s.contains("birds_nest") || s.contains("tulou")
                        || s.contains("temple") || s.contains("pagoda")) {
                    return true;
                }
            }
        }
        if (c.features() == null) return false;
        return c.features().stream().anyMatch(f -> f != null && (
                f.toLowerCase(Locale.ROOT).contains("gothic")
                        || f.toLowerCase(Locale.ROOT).contains("triumphal")
                        || f.toLowerCase(Locale.ROOT).contains("archway")
                        || f.toLowerCase(Locale.ROOT).contains("stadium")
                        || f.toLowerCase(Locale.ROOT).contains("ribbon_glazing")
                        || f.contains("哥特") || f.contains("凯旋") || f.contains("门楼")
                        || f.contains("体育场")));
    }

    private static boolean planHasStadiumExecutor(LlmPlan plan) {
        if (plan == null || plan.components() == null) return false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!("STRUCTURE".equals(t) || "MODULE".equals(t) || t.startsWith("TOWER"))) continue;
            if (isForeignStyleHost(c) && (componentId(c).toLowerCase(Locale.ROOT).contains("stadium")
                    || componentId(c).toLowerCase(Locale.ROOT).contains("arena")
                    || (c.params() != null && String.valueOf(c.params().getOrDefault("typology_id", ""))
                    .toLowerCase(Locale.ROOT).contains("stadium")))) {
                return true;
            }
            if (c.params() != null) {
                Object typ = c.params().get("typology_id");
                if (typ == null) typ = c.params().get("module_id");
                if (typ != null && String.valueOf(typ).toLowerCase(Locale.ROOT).contains("stadium")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Component belongs to the unique baroque target via id or host_id chain. */
    private static boolean belongsToBaroqueTarget(
            Component c, String baroqueTarget, List<Component> components) {
        if (c == null || baroqueTarget == null || baroqueTarget.isBlank()) {
            return false;
        }
        if (isForeignStyleHost(c)) {
            return false;
        }
        if (baroqueTarget.equals(componentId(c)) || baroqueTarget.equals(hostIdOf(c))) {
            return true;
        }
        // Walk host_id → parent until target or cycle.
        Map<String, String> parentById = new HashMap<>();
        if (components != null) {
            for (Component other : components) {
                if (other == null) continue;
                String oid = componentId(other);
                if (oid.isBlank()) continue;
                String parent = hostIdOf(other);
                if (parent != null && !parent.isBlank()) {
                    parentById.put(oid, parent);
                }
            }
        }
        String cursor = hostIdOf(c);
        if (cursor == null || cursor.isBlank()) {
            cursor = componentId(c);
        }
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        while (cursor != null && !cursor.isBlank() && seen.add(cursor)) {
            if (baroqueTarget.equals(cursor)) {
                return true;
            }
            cursor = parentById.get(cursor);
        }
        return false;
    }

    private static Component enrich(
            LlmPlan plan, Component c, String corpus, boolean corpusWantsMansard, boolean corpusWantsDormers,
            String baroqueTarget) {
        if (c == null || c.componentType() == null) return c;
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        boolean changed = false;
        Dimensions dims = c.dimensions();
        String hostId = hostIdOf(c);
        if (hostId == null || hostId.isBlank()) {
            hostId = componentId(c);
        }
        boolean baroqueOwned = belongsToBaroqueTarget(c, baroqueTarget, plan.components());
        boolean hostMansard = hostHasMansard(plan, plan.components(), hostId);
        boolean hostDormers = hostWantsDormers(plan, plan.components(), hostId, corpusWantsDormers);
        // Authored mansard / features may refine the baroque host. Corpus 「马萨」 alone must not
        // overwrite an already-authored hip/gable on the baroque main when mansard belongs to an annex.
        boolean mayForceMansard = baroqueOwned && (hostMansard || featuresSayMansard(features));
        boolean mayFillBlankMansard = baroqueOwned && corpusWantsMansard && hostId.equals(baroqueTarget);

        // Mass/STRUCTURE plinth, storey, and material enrich — baroque target only.
        if ((type.startsWith("MASS") || "STRUCTURE".equals(type)) && baroqueOwned) {
            boolean plate = isFloorPlate(c) || isFloorPlateParams(params);
            if (plate && dims != null && dims.height() > 1) {
                dims = new Dimensions(dims.width(), dims.depth(), 1);
                changed = true;
            }
            if (!plate) {
                // Only lift unlocked unusable clear height; never invent a fixed mansion storey size.
                int minBand = InteriorSpaceDefaults.minStoreyBand();
                int floorHeight = readPositiveInt(params, "floor_height", "floorHeight", "storey_height");
                if (floorHeight > 0 && floorHeight < minBand && !corpusContainsLowCeiling(corpus)
                        && DesignProvenance.putIfAllowed(plan, params, "floor_height", minBand)) {
                    floorHeight = minBand;
                    changed = true;
                }
                int groundClear = readPositiveInt(params, "ground_clear_height", "groundClearHeight");
                if (groundClear > 0 && groundClear < minBand && !corpusContainsLowCeiling(corpus)
                        && DesignProvenance.putIfAllowed(plan, params, "ground_clear_height", minBand)) {
                    groundClear = minBand;
                    changed = true;
                }
                // Expand envelope only when height is unlocked and fits LLM floor_count × bands.
                int floors = readPositiveInt(params, "floor_count", "floorCount", "levels");
                if (dims != null && floors > 0 && floorHeight > 0 && !corpusContainsLowCeiling(corpus)
                        && DesignProvenance.canMutateParam(plan, params, "height")) {
                    int upper = Math.max(minBand, floorHeight);
                    int ground = groundClear > 0 ? Math.max(minBand, groundClear) : upper;
                    int minHeight = ground + Math.max(0, floors - 1) * upper;
                    if (dims.height() < minHeight) {
                        dims = new Dimensions(dims.width(), dims.depth(), minHeight);
                        changed = true;
                    }
                }
                if (DesignProvenance.canEnrichFeatures(plan, params)
                        && features.stream().noneMatch(f -> f != null
                        && f.toLowerCase(Locale.ROOT).contains("base_plinth"))) {
                    features.add("base_plinth");
                    changed = true;
                }
                if (!params.containsKey("facade_profile") && !params.containsKey("facadeProfile")) {
                    params.put("facade_profile", "base_plinth");
                    changed = true;
                }
                String plinth = resolvePlinthBlock(plan);
                if (plinth != null) {
                    if (params.putIfAbsent("foundation_block", plinth) == null) changed = true;
                    if (params.putIfAbsent("plinth_block", plinth) == null) changed = true;
                    if (params.putIfAbsent("base_block", plinth) == null) changed = true;
                }
                String wall = resolveUpperWall(plan);
                if (wall != null && params.putIfAbsent("wall_block", wall) == null) {
                    changed = true;
                }
                // Mass-embedded roof_type: never overwrite authored non-mansard; blank fill from corpus.
                Object massRt = params.get("roof_type");
                if (massRt == null) massRt = params.get("roofType");
                boolean blankRoof = massRt == null || String.valueOf(massRt).isBlank();
                boolean alreadyMansard = massRt != null
                        && String.valueOf(massRt).toLowerCase(Locale.ROOT).contains("mansard");
                if ((mayForceMansard && !alreadyMansard && blankRoof)
                        || (mayFillBlankMansard && blankRoof)) {
                    if (DesignProvenance.putIfAllowed(plan, params, "roof_type", "mansard")) {
                        params.put("preferred_roof_type", "mansard");
                        changed = true;
                    }
                }
            }
        }
        if (type.startsWith("ROOF") && baroqueOwned) {
            Object rt = params.get("roof_type");
            if (rt == null) rt = params.get("roofType");
            boolean mansard = rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard");
            boolean blankRoof = rt == null || String.valueOf(rt).isBlank();
            // Authored hip/gable/flat/xieshan stay — sibling mansard on the same host must not unify them.
            // Only blank roofs may receive mansard (host cue or corpus fill on the baroque target).
            if (!mansard && blankRoof && (mayForceMansard || mayFillBlankMansard)) {
                if (DesignProvenance.putIfAllowed(plan, params, "roof_type", "mansard")) {
                    DesignProvenance.putIfAllowed(plan, params, "roof_specialty",
                            hostDormers ? "mansard_dormer" : "mansard");
                    if (hostDormers) {
                        DesignProvenance.putIfAllowed(plan, params, "roof_dormers", true);
                    } else {
                        params.putIfAbsent("roof_dormers", false);
                    }
                    mansard = true;
                    changed = true;
                }
            } else if (mansard && hostDormers) {
                if (!Boolean.TRUE.equals(params.get("roof_dormers"))
                        && DesignProvenance.putIfAllowed(plan, params, "roof_dormers", true)) {
                    DesignProvenance.putIfAllowed(plan, params, "roof_specialty", "mansard_dormer");
                    changed = true;
                }
            } else if (mansard && Boolean.TRUE.equals(params.get("roof_dormers"))
                    && !hostDormers
                    && features.stream().noneMatch(f -> f != null
                    && (f.toLowerCase(Locale.ROOT).contains("dormer") || f.contains("老虎窗")))
                    && DesignProvenance.putIfAllowed(plan, params, "roof_dormers", false)) {
                changed = true;
            }
            if (mansard && DesignProvenance.canEnrichFeatures(plan, params)
                    && features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
                features.add("mansard");
                changed = true;
            }
            String roofBlock = resolveRoofBlock(plan);
            if (roofBlock != null && params.putIfAbsent("roof_block", roofBlock) == null) {
                changed = true;
            }
        }
        Vec3i position = c.relativePosition();
        if (baroqueOwned && ("PAVING".equals(type) || "PLAZA".equals(type))) {
            if (!params.containsKey("role")) {
                params.put("role", "entry_plaza");
                changed = true;
            }
            if (!params.containsKey("entrance_id") && !params.containsKey("entranceId")) {
                params.put("entrance_id", "main_entrance");
                changed = true;
            }
        }
        if (baroqueOwned && "ENTRANCE".equals(type) && position != null && position.y() <= 0) {
            position = new Vec3i(position.x(), 1, position.z());
            changed = true;
        }
        if ((type.contains("FACADE") || type.contains("WINDOW")) && baroqueOwned) {
            // Component/host features only — plan distinguishingFeatures must not rewrite annex windows.
            Object aspect = params.get("window_aspect");
            if (aspect == null) aspect = params.get("windowAspect");
            String aspectStr = aspect == null ? "" : String.valueOf(aspect).toLowerCase(Locale.ROOT);
            if (!aspectStr.isBlank() && !aspectStr.contains("ribbon") && !aspectStr.contains("pilaster")) {
                // Authored aspect (incl. ribbon-free strip/horizontal) stays.
            } else {
                boolean archedIntent = features.stream().anyMatch(f -> f != null && (
                        f.toLowerCase(Locale.ROOT).contains("arch") || f.contains("拱")));
                if (archedIntent && (aspectStr.isBlank() || aspectStr.contains("ribbon")
                        || aspectStr.contains("pilaster"))
                        && DesignProvenance.putIfAllowed(plan, params, "window_aspect", "arched")) {
                    DesignProvenance.putIfAllowed(plan, params, "window_style", "arched");
                    changed = true;
                }
            }
        }
        if (!changed) {
            return c;
        }
        return new Component(c.componentType(), c.slotId(), position,
                dims != null ? dims : c.dimensions(), features, params);
    }

    private static boolean featuresSayMansard(List<String> features) {
        return features != null && features.stream().anyMatch(f -> f != null
                && f.toLowerCase(Locale.ROOT).contains("mansard"));
    }

    private static int readPositiveInt(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object v = params.get(key);
            if (v instanceof Number n) {
                int i = n.intValue();
                if (i > 0) return i;
            } else if (v != null) {
                try {
                    int i = Integer.parseInt(String.valueOf(v).trim());
                    if (i > 0) return i;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static boolean corpusContainsLowCeiling(String corpus) {
        return corpus.contains("层高2") || corpus.contains("层高两") || corpus.contains("二格高")
                || corpus.contains("2格高") || corpus.contains("矮层")
                || corpus.contains("ceiling 2") || corpus.contains("height 2")
                || corpus.contains("low ceiling") || corpus.contains("矮净高");
    }

    private static String resolvePlinthBlock(LlmPlan plan) {
        StyleAttributes sa = plan.styleAttributes();
        if (sa == null) return null;
        String fromPalette = sa.resolvePaletteBlock(
                "plinth", "base", "foundation_band", "foundation", "base_material");
        if (fromPalette != null && !fromPalette.isBlank()) {
            return fromPalette;
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

    private static String hostIdOf(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        if (host == null) return null;
        String s = String.valueOf(host).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static String componentId(Component c) {
        if (c == null || c.params() == null) return "";
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? "" : String.valueOf(id).trim();
    }
}
