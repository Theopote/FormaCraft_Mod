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
        if (looksMonumentGatePlan(plan) || looksMonumentGateCorpus(corpus)) {
            return plan;
        }
        if (looksNonBaroqueResidential(plan, corpus)) {
            return plan;
        }
        if (!looksBaroqueMansion(plan, corpus)) {
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

        boolean wantsDormers = planWantsDormers(plan, corpus);
        boolean wantsMansard = planWantsMansard(plan, corpus);
        Dimensions primaryFootprint = primaryMassFootprint(plan.components());
        List<Component> out = new ArrayList<>(plan.components().size());
        for (Component c : plan.components()) {
            if (c == null) {
                continue;
            }
            // Full-footprint DECOR "dormers" paint concentric roof rings — drop and let specialty emit.
            if (isOversizedRoofDormerDecor(c, primaryFootprint)) {
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
            // Keep authored chimneys (symmetric pairs / annex stacks) — only drop geometric noise above.
            Component next = enrich(plan, c, corpus, wantsMansard, wantsDormers);
            if (next != c) {
                changed = true;
            }
            out.add(next);
        }
        if (wantsDormers) {
            List<Component> withRoof = new ArrayList<>(out.size());
            boolean roofTouched = false;
            for (Component c : out) {
                if (c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")
                        && roofIsMansard(c)) {
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
        }

        Map<String, Object> hints = plan.proportionHints() == null
                ? new HashMap<>()
                : new HashMap<>(plan.proportionHints());
        Object typology = hints.get("typology");
        String typ = typology == null ? "" : String.valueOf(typology).toLowerCase(Locale.ROOT);
        // Only rewrite clearly wrong temple/stadium typology — leave other LLM typologies alone.
        if (typ.contains("stadium") || typ.contains("temple") || typ.contains("pantheon")) {
            hints.put("typology", "baroque_mansion");
            changed = true;
        }
        if (!hints.containsKey("roof_specialty") && wantsMansard && planHasMansard(plan)) {
            hints.put("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
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

    private static Dimensions primaryMassFootprint(List<Component> components) {
        for (Component c : components) {
            if (c == null || c.componentType() == null || c.dimensions() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (type.startsWith("MASS") || "MAIN_MASS".equals(type)) {
                return c.dimensions();
            }
        }
        return null;
    }

    /**
     * Thin wall cantilevers and attic spikes often arrive as BALCONY / DECOR and land in the
     * wrong place (deepslate beams mid-facade, quartz columns atop the mansard).
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
            // Beam-like relative to its own span: short, thin on one axis, elongated on the other.
            boolean beamLike = d.height() <= 2
                    && Math.min(d.width(), d.depth()) <= 2
                    && Math.max(d.width(), d.depth()) >= Math.min(d.width(), d.depth()) * 3;
            if (beamLike) {
                return true;
            }
            boolean labelled = id.contains("balcony") || id.contains("terrace") || id.contains("deck")
                    || id.contains("阳台") || id.contains("露台");
            if (!labelled && d.height() <= 2 && Math.min(d.width(), d.depth()) <= 3) {
                return true;
            }
        }
        if ("DECOR_DETAIL".equals(type) || "DECOR".equals(type)) {
            boolean atticCue = id.contains("pediment") || id.contains("dormer") || id.contains("attic")
                    || id.contains("column") || id.contains("pilaster") || id.contains("山花")
                    || id.contains("老虎窗");
            // Tall thin spike relative to its own footprint — not a real roof shell.
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

    private static boolean planHasMansard(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object rt = c.params().get("roof_type");
            if (rt == null) rt = c.params().get("roofType");
            if (rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
                return true;
            }
            if (c.features() != null && c.features().stream().anyMatch(f -> f != null
                    && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean planWantsDormers(LlmPlan plan, String corpus) {
        if (plan.proportionHints() != null) {
            Object specialty = plan.proportionHints().get("roof_specialty");
            if (specialty != null && String.valueOf(specialty).toLowerCase(Locale.ROOT).contains("dormer")) {
                return true;
            }
        }
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && (f.toLowerCase(Locale.ROOT).contains("dormer")
                        || f.contains("老虎窗"))) {
                    return true;
                }
            }
        }
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (c.params() != null) {
                if (Boolean.TRUE.equals(c.params().get("roof_dormers"))) return true;
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
        // Affirmative dormer ask only — 「不要老虎窗」must not authorize dormers.
        return hasAffirmativeCue(corpus, List.of("老虎窗", "dormer", "mansard_dormer"));
    }

    /** Explicit mansard ask or authored mansard on plan — baroque style alone is not enough. */
    private static boolean planWantsMansard(LlmPlan plan, String corpus) {
        if (planHasMansard(plan)) return true;
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

    private static boolean looksNonBaroqueResidential(LlmPlan plan, String corpus) {
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        if (style.contains("victorian") || style.contains("gothic") || style.contains("tudor")
                || style.contains("craftsman") || style.contains("japanese")
                || style.contains("chinese") || style.contains("industrial")
                || style.contains("cyberpunk") || style.contains("art_deco")
                || style.contains("art-deco")) {
            return true;
        }
        // Style family names only — not every residential synonym.
        return corpus.contains("victorian") || corpus.contains("维多利亚")
                || corpus.contains("gothic") || corpus.contains("哥特")
                || corpus.contains("tudor") || corpus.contains("都铎");
    }

    private static boolean looksMonumentGateCorpus(String corpus) {
        return corpus.contains("triumphal") || corpus.contains("凯旋")
                || corpus.contains("victory arch") || corpus.contains("门楼") || corpus.contains("牌楼");
    }

    private static boolean looksMonumentGatePlan(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            String id = c.params() == null ? ""
                    : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            if (id.contains("arch") || id.contains("gate") || id.contains("triumphal")) return true;
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && (f.toLowerCase(Locale.ROOT).contains("triumphal")
                            || f.toLowerCase(Locale.ROOT).contains("archway")
                            || f.contains("凯旋"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Component enrich(
            LlmPlan plan, Component c, String corpus, boolean wantsMansard, boolean wantsDormers) {
        if (c == null || c.componentType() == null) return c;
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        boolean changed = false;
        Dimensions dims = c.dimensions();

        if (type.startsWith("MASS") || "STRUCTURE".equals(type)) {
            Object extrude = params.get("extrude_mode");
            if (extrude == null) extrude = params.get("extrudeMode");
            String id = String.valueOf(params.getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            boolean plate = (extrude != null && String.valueOf(extrude).toLowerCase(Locale.ROOT).contains("plate"))
                    || id.contains("floor_plate") || id.contains("floorplate") || id.endsWith("_plate");
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
            }
            if (DesignProvenance.canEnrichFeatures(plan, params)
                    && features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("base_plinth"))) {
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
            // Mass-embedded roof_type: only when mansard is already planned/asked — never baroque alone.
            if (wantsMansard && (planHasMansard(plan) || featuresSayMansard(features))) {
                Object rt = params.get("roof_type");
                if (rt == null) rt = params.get("roofType");
                if (rt == null || !String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
                    if (DesignProvenance.putIfAllowed(plan, params, "roof_type", "mansard")) {
                        params.put("preferred_roof_type", "mansard");
                        changed = true;
                    }
                }
            }
        }
        if (type.startsWith("ROOF")) {
            Object rt = params.get("roof_type");
            if (rt == null) rt = params.get("roofType");
            boolean mansard = rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard");
            // Never use looksBaroqueMansion alone to force mansard — preserves hip/flat locks.
            boolean mayForceMansard = wantsMansard
                    && (planHasMansard(plan) || featuresSayMansard(features));
            if (!mansard && mayForceMansard) {
                if (DesignProvenance.putIfAllowed(plan, params, "roof_type", "mansard")) {
                    DesignProvenance.putIfAllowed(plan, params, "roof_specialty",
                            wantsDormers ? "mansard_dormer" : "mansard");
                    if (wantsDormers) {
                        DesignProvenance.putIfAllowed(plan, params, "roof_dormers", true);
                    } else {
                        params.putIfAbsent("roof_dormers", false);
                    }
                    mansard = true;
                    changed = true;
                }
            } else if (mansard && wantsDormers) {
                if (!Boolean.TRUE.equals(params.get("roof_dormers"))
                        && DesignProvenance.putIfAllowed(plan, params, "roof_dormers", true)) {
                    DesignProvenance.putIfAllowed(plan, params, "roof_specialty", "mansard_dormer");
                    changed = true;
                }
            } else if (mansard && Boolean.TRUE.equals(params.get("roof_dormers"))
                    && !wantsDormers
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
        if ("PAVING".equals(type) || "PLAZA".equals(type)) {
            if (!params.containsKey("role")) {
                params.put("role", "entry_plaza");
                changed = true;
            }
            if (!params.containsKey("entrance_id") && !params.containsKey("entranceId")) {
                params.put("entrance_id", "main_entrance");
                changed = true;
            }
        }
        Vec3i position = c.relativePosition();
        if ("ENTRANCE".equals(type) && position != null && position.y() <= 0) {
            position = new Vec3i(position.x(), 1, position.z());
            changed = true;
        }
        if (type.contains("FACADE") || type.contains("WINDOW")) {
            // Honour LLM window_aspect when present; only clear ribbon/pilaster misfits for arched intent.
            Object aspect = params.get("window_aspect");
            if (aspect == null) aspect = params.get("windowAspect");
            String aspectStr = aspect == null ? "" : String.valueOf(aspect).toLowerCase(Locale.ROOT);
            boolean archedIntent = features.stream().anyMatch(f -> f != null && (
                    f.toLowerCase(Locale.ROOT).contains("arch") || f.contains("拱")))
                    || (plan.distinguishingFeatures() != null && plan.distinguishingFeatures().stream()
                    .anyMatch(f -> f != null && (f.toLowerCase(Locale.ROOT).contains("arch")
                            || f.contains("拱"))));
            if (archedIntent && (aspectStr.isBlank() || aspectStr.contains("ribbon") || aspectStr.contains("pilaster"))) {
                params.put("window_aspect", "arched");
                params.put("window_style", "arched");
                changed = true;
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
}
