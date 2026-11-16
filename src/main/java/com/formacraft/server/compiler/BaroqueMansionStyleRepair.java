package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
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
 * Baroque mansions must keep rectangular 15×25 massing, mansard roofs, and plinth banding.
 * LLM often labels them {@code Classical_GrecoRoman}, which then triggers temple squaring
 * and classical pilaster window rhythm instead of arched quartz openings + mansard.
 */
final class BaroqueMansionStyleRepair {
    private BaroqueMansionStyleRepair() {}

    private static final List<String> BAROQUE_CUES = List.of(
            "巴洛克", "baroque", "大宅", "宅邸", "府邸", "公馆", "庄园", "马萨", "mansard",
            "折线屋顶", "折线形屋顶", "老虎窗", "mansion");

    private static final List<String> MONUMENT_GATE_CUES = List.of(
            "凯旋", "凯旋门", "triumphal", "victory arch", "门楼", "牌楼", "arch of");

    private static final List<String> DORMER_CUES = List.of(
            "老虎窗", "dormer", "mansard_dormer", "椭圆山花");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = requestCorpus(plan);
        // Baroque + 凯旋门 is a monument gate, not a mansard mansion with dormers.
        if (containsAny(corpus, MONUMENT_GATE_CUES) || looksMonumentGatePlan(plan)) {
            return plan;
        }
        // Victorian / Gothic / etc. often say 大宅 — do not force mansard + dormers onto them.
        if (looksNonBaroqueResidential(plan, corpus)) {
            return plan;
        }
        if (!containsAny(corpus, BAROQUE_CUES) && !hasBaroqueMarkers(plan)) {
            return plan;
        }
        // Bare "大宅/mansion" without baroque/mansard cues is not enough.
        if (!containsAny(corpus, List.of("巴洛克", "baroque", "马萨", "mansard", "折线屋顶", "折线形屋顶", "老虎窗"))
                && !hasBaroqueMarkers(plan)) {
            return plan;
        }

        boolean changed = false;
        String style = plan.styleProfile();
        if (styleLooksClassicalTemple(style)) {
            style = "Legacy_Classical_Facade";
            changed = true;
        }

        boolean wantsDormers = containsAny(corpus, DORMER_CUES);
        Dimensions primaryFootprint = primaryMassFootprint(plan.components());
        List<Component> out = new ArrayList<>(plan.components().size());
        int keptChimneys = 0;
        for (Component c : plan.components()) {
            if (c == null) {
                continue;
            }
            // Full-footprint DECOR "oval_pediment_dormers" paints three stacked roof rings —
            // drop it and let the mansard roof specialty emit real dormers instead.
            if (isOversizedRoofDormerDecor(c, primaryFootprint)) {
                changed = true;
                FormacraftMod.LOGGER.info(
                        "BaroqueMansionStyleRepair: drop oversized roof decor id={}",
                        c.params() == null ? c.componentType() : c.params().get("component_id"));
                continue;
            }
            if ("CHIMNEY".equalsIgnoreCase(c.componentType())) {
                keptChimneys++;
                if (keptChimneys > 1) {
                    changed = true;
                    continue;
                }
            }
            Component next = enrich(plan, c, corpus, wantsDormers);
            if (next != c) {
                changed = true;
            }
            out.add(next);
        }
        // Ensure mansard dormers survive after dropping the oversized DECOR plate.
        if (wantsDormers) {
            List<Component> withRoof = new ArrayList<>(out.size());
            boolean roofTouched = false;
            for (Component c : out) {
                if (c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")) {
                    Component ensured = ensureMansardDormers(c);
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
        if (typology == null || String.valueOf(typology).toLowerCase(Locale.ROOT).contains("stadium")
                || String.valueOf(typology).toLowerCase(Locale.ROOT).contains("temple")) {
            hints.put("typology", "baroque_mansion");
            changed = true;
        }
        if (!hints.containsKey("roof_specialty")) {
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
        return w >= Math.max(8, primary.width() - 2) && d >= Math.max(8, primary.depth() - 2);
    }

    private static Component ensureMansardDormers(Component roof) {
        Map<String, Object> params = roof.params() == null ? new HashMap<>() : new HashMap<>(roof.params());
        boolean changed = false;
        if (!Boolean.TRUE.equals(params.get("roof_dormers"))) {
            params.put("roof_dormers", true);
            changed = true;
        }
        Object specialty = params.get("roof_specialty");
        if (specialty == null || !String.valueOf(specialty).toLowerCase(Locale.ROOT).contains("dormer")) {
            params.put("roof_specialty", "mansard_dormer");
            changed = true;
        }
        if (!params.containsKey("dormer_count") && !params.containsKey("mansard_dormer_count")) {
            params.put("dormer_count", 3);
            changed = true;
        }
        if (!changed) return roof;
        return new Component(roof.componentType(), roof.slotId(), roof.relativePosition(),
                roof.dimensions(), roof.features(), params);
    }

    private static boolean styleLooksClassicalTemple(String style) {
        if (style == null || style.isBlank()) return false;
        String s = style.toLowerCase(Locale.ROOT);
        return s.contains("grecoroman") || s.contains("greco_roman") || s.contains("stadium")
                || s.contains("pantheon");
    }

    private static boolean hasBaroqueMarkers(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            String id = c.params() == null ? ""
                    : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            if (id.contains("mansion") || id.contains("baroque")) return true;
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

    private static boolean looksNonBaroqueResidential(LlmPlan plan, String corpus) {
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        if (style.contains("victorian") || style.contains("gothic") || style.contains("tudor")
                || style.contains("craftsman") || style.contains("japanese")
                || style.contains("chinese") || style.contains("industrial")
                || style.contains("cyberpunk") || style.contains("art_deco")
                || style.contains("art-deco")) {
            return true;
        }
        return containsAny(corpus, List.of(
                "维多利亚", "victorian", "哥特", "gothic", "都铎", "tudor",
                "葱头", "onion", "日式", "中式", "废土"));
    }

    private static boolean looksMonumentGatePlan(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            if (id.contains("arch") || id.contains("凯旋") || id.contains("triumphal")
                    || id.contains("gate_body") || id.contains("arch_body")) {
                return true;
            }
            Object buildingClass = plan.planMeta() == null ? null : plan.planMeta().get("building_class");
            if (buildingClass != null && "gate".equalsIgnoreCase(String.valueOf(buildingClass))) {
                return true;
            }
        }
        return false;
    }

    private static Component enrich(LlmPlan plan, Component c, String corpus, boolean wantsDormers) {
        if (c == null || c.componentType() == null) return c;
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        boolean changed = false;
        Dimensions dims = c.dimensions();

        if (type.startsWith("MASS") || "STRUCTURE".equals(type)) {
            // Floor plates must stay thin slabs — height 6 “plates” crush upper storeys visually.
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
                Object fh = params.get("floor_height");
                if (fh == null) fh = params.get("floorHeight");
                int floorHeight = 0;
                if (fh instanceof Number n) floorHeight = n.intValue();
                else if (fh != null) {
                    try { floorHeight = Integer.parseInt(String.valueOf(fh).trim()); }
                    catch (NumberFormatException ignored) {}
                }
                // Default storey clear height must never fall below 3 unless user asked smaller.
                if (floorHeight > 0 && floorHeight < 3 && !corpusContainsLowCeiling(corpus)) {
                    params.put("floor_height", 3);
                    changed = true;
                } else if (floorHeight <= 0) {
                    params.put("floor_height", 4);
                    changed = true;
                }
                if (dims != null) {
                    Object fc = params.get("floor_count");
                    if (fc == null) fc = params.get("floorCount");
                    int floors = fc instanceof Number n ? n.intValue() : 0;
                    int minHeight = Math.max(3, (floors > 0 ? floors : 2) * 3);
                    if (dims.height() < minHeight && !corpusContainsLowCeiling(corpus)) {
                        dims = new Dimensions(dims.width(), dims.depth(), minHeight);
                        changed = true;
                    }
                }
            }
            if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("base_plinth"))) {
                features.add("base_plinth");
                changed = true;
            }
            if (!params.containsKey("facade_profile") && !params.containsKey("facadeProfile")) {
                params.put("facade_profile", "base_plinth");
                changed = true;
            }
            String plinth = resolvePlinthBlock(plan, corpus);
            if (plinth != null) {
                if (params.putIfAbsent("foundation_block", plinth) == null) changed = true;
                if (params.putIfAbsent("plinth_block", plinth) == null) changed = true;
                if (params.putIfAbsent("base_block", plinth) == null) changed = true;
            }
            String wall = resolveUpperWall(plan);
            if (wall != null && params.putIfAbsent("wall_block", wall) == null) {
                changed = true;
            }
            if (containsAny(corpus, List.of("马萨", "mansard", "折线"))) {
                Object rt = params.get("roof_type");
                if (rt == null) rt = params.get("roofType");
                if (rt == null || !String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
                    params.put("roof_type", "mansard");
                    params.put("preferred_roof_type", "mansard");
                    changed = true;
                }
            }
        }
        if (type.startsWith("ROOF")) {
            Object rt = params.get("roof_type");
            if (rt == null) rt = params.get("roofType");
            if (rt == null || !String.valueOf(rt).toLowerCase(Locale.ROOT).contains("mansard")) {
                params.put("roof_type", "mansard");
                params.put("roof_specialty", wantsDormers ? "mansard_dormer" : "mansard");
                if (wantsDormers) {
                    params.put("roof_dormers", true);
                } else {
                    params.putIfAbsent("roof_dormers", false);
                }
                changed = true;
            } else if (wantsDormers) {
                if (!Boolean.TRUE.equals(params.get("roof_dormers"))) {
                    params.put("roof_dormers", true);
                    params.put("roof_specialty", "mansard_dormer");
                    changed = true;
                }
            } else if (Boolean.TRUE.equals(params.get("roof_dormers"))
                    && features.stream().noneMatch(f -> f != null
                    && (f.toLowerCase(Locale.ROOT).contains("dormer") || f.contains("老虎窗")))) {
                params.put("roof_dormers", false);
                changed = true;
            }
            if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("mansard"))) {
                features.add("mansard");
                changed = true;
            }
            String roofBlock = resolveRoofBlock(plan);
            if (roofBlock != null && params.putIfAbsent("roof_block", roofBlock) == null) {
                changed = true;
            }
        }
        if ("PAVING".equals(type) || "PLAZA".equals(type)) {
            // Link forecourt to the main entrance so threshold re-alignment can seat the plaza.
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
            // Ensure a solid sill band: door opens at least one above mass bottom.
            position = new Vec3i(position.x(), 1, position.z());
            changed = true;
        }
        if (type.contains("FACADE") || type.contains("WINDOW")) {
            if (containsAny(corpus, List.of("拱形", "arched", "石英"))) {
                Object aspect = params.get("window_aspect");
                if (aspect == null || String.valueOf(aspect).toLowerCase(Locale.ROOT).contains("ribbon")
                        || String.valueOf(aspect).toLowerCase(Locale.ROOT).contains("pilaster")) {
                    params.put("window_aspect", "arched");
                    params.put("window_style", "arched");
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

    private static boolean corpusContainsLowCeiling(String corpus) {
        return containsAny(corpus, List.of("层高2", "层高两", "二格高", "2格高", "矮层", "ceiling 2", "height 2"));
    }

    private static String resolvePlinthBlock(LlmPlan plan, String corpus) {
        StyleAttributes sa = plan.styleAttributes();
        if (sa != null) {
            String fromPalette = sa.resolvePaletteBlock(
                    "plinth", "base", "foundation_band", "foundation", "base_material");
            if (fromPalette != null && !fromPalette.isBlank()) {
                return fromPalette;
            }
        }
        if (containsAny(corpus, List.of("深板", "深板岩", "deepslate"))) {
            return "minecraft:deepslate_bricks";
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

    private static boolean containsAny(String corpus, List<String> cues) {
        for (String cue : cues) {
            if (cue != null && !cue.isBlank() && corpus.contains(cue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
