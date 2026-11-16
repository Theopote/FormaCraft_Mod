package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.impl.WindowAspect;
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
 * Compositional gothic bell towers often arrive with flat roofs, glass panes, and
 * off-center doors despite style_attributes requesting iron-bar slits / mixed masonry /
 * steep spires. Nudge params so catalog execution matches the playtest contract.
 */
final class GothicBellTowerFidelityRepair {
    private GothicBellTowerFidelityRepair() {}

    private static final List<String> GOTHIC_CUES = List.of(
            "哥特", "gothic", "钟楼", "bell tower", "bell_tower", "clocktower", "clock tower");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = requestCorpus(plan);
        if (!looksGothicBellTower(plan, corpus)) {
            return plan;
        }

        String glazing = resolveIronBarsGlazing(plan);
        String accent = resolveAccent(plan);
        boolean changed = false;
        List<Component> out = new ArrayList<>(plan.components().size());
        Component primaryMass = findPrimaryTowerMass(plan.components());

        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) {
                out.add(c);
                continue;
            }
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            Component next = c;
            if ("STRUCTURE".equals(type) && looksButtressAccessory(c)) {
                next = remapButtressToMass(c, primaryMass);
            } else if (type.startsWith("MASS") || "MAIN_MASS".equals(type)) {
                next = repairMass(c, accent, corpus);
            } else if ("FACADE_WINDOWS".equals(type)) {
                next = repairWindows(c, glazing);
            } else if ("ROOF".equals(type) || type.startsWith("ROOF")) {
                next = repairRoof(c, corpus);
            } else if ("ENTRANCE".equals(type)) {
                next = repairEntrance(c, primaryMass, plan);
            }
            if (next != c) {
                changed = true;
            }
            out.add(next);
        }
        if (!changed) {
            return plan;
        }
        FormacraftMod.LOGGER.info(
                "GothicBellTowerFidelityRepair: applied iron-bar slits / steep spire / centered door nudges");
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static boolean looksButtressAccessory(Component c) {
        String id = c.params() == null ? ""
                : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
        if (id.contains("buttress") || id.contains("pier") || id.contains("pinnacle")) return true;
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("buttress") || lower.contains("pier") || lower.contains("飞扶壁")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Component remapButtressToMass(Component c, Component primaryMass) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        params.put("extrude_mode", "solid");
        params.put("hollow", false);
        if (primaryMass != null && primaryMass.params() != null) {
            Object host = primaryMass.params().get("component_id");
            if (host != null && !params.containsKey("host_id")) {
                params.put("host_id", host);
            }
        }
        FormacraftMod.LOGGER.info(
                "GothicBellTowerFidelityRepair: STRUCTURE→MASS_SECONDARY buttress id={}",
                params.get("component_id"));
        return new Component("MASS_SECONDARY", c.slotId(), c.relativePosition(),
                c.dimensions(), c.features(), params);
    }

    private static Component repairMass(Component c, String accent, String corpus) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        boolean changed = false;
        Object pattern = params.get("wall_pattern");
        if (pattern == null) pattern = params.get("wallPattern");
        if (pattern == null || String.valueOf(pattern).isBlank()) {
            params.put("wall_pattern", "gradient");
            changed = true;
        }
        if (accent != null && !params.containsKey("trim_block") && !params.containsKey("accent_block")) {
            params.put("trim_block", accent);
            changed = true;
        }
        // Lower shaft should not declare a pitched roof when a separate spire ROOF exists.
        Object rt = params.get("roof_type");
        if (rt == null) rt = params.get("roofType");
        if (rt != null && "flat".equalsIgnoreCase(String.valueOf(rt))
                && (corpus.contains("尖") || corpus.contains("spire") || corpus.contains("尖顶"))) {
            params.remove("roof_type");
            params.remove("roofType");
            changed = true;
        }
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("mixed"))) {
            features.add("mixed_masonry");
            changed = true;
        }
        if (!changed) return c;
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), features, params);
    }

    private static Component repairWindows(Component c, String glazing) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        boolean changed = false;
        String style = String.valueOf(params.getOrDefault("window_style",
                params.getOrDefault("windowStyle", ""))).toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        // Plain "slit" + window_aspect=tall hard-fails E_WINDOW_ASPECT_CONFLICT. Prefer slit_bars
        // (iron-bar gothic slits) which does not conflict, and keep aspect on arrow_slit.
        boolean plainSlit = "slit".equals(style) || "arrow_slit".equals(style)
                || "arrow_slits".equals(style) || "slit_window".equals(style);
        if (plainSlit || (!style.contains("bar") && !style.contains("slit") && !style.contains("lattice"))) {
            params.put("window_style", "slit_bars");
            style = "slit_bars";
            changed = true;
        }
        Object aspect = params.get("window_aspect");
        if (aspect == null) aspect = params.get("windowAspect");
        String aspectRaw = aspect == null ? "" : String.valueOf(aspect).trim();
        String resolvedStyle = String.valueOf(params.getOrDefault("window_style", style));
        // "tall" is not a real WindowAspect token (parses as SQUARE) and conflicts with plain slit.
        if (aspectRaw.isBlank()
                || "tall".equalsIgnoreCase(aspectRaw)
                || WindowAspect.conflictsWithWindowStyle(WindowAspect.parse(aspectRaw), resolvedStyle)) {
            params.put("window_aspect", "arrow_slit");
            changed = true;
        }
        if (glazing != null) {
            for (String key : List.of("glass_block", "glazing_block", "glass_material",
                    "window_block", "window_material")) {
                Object cur = params.get(key);
                if (cur != null && String.valueOf(cur).toLowerCase(Locale.ROOT).contains("glass")
                        && !String.valueOf(cur).toLowerCase(Locale.ROOT).contains("bars")) {
                    params.put(key, glazing);
                    changed = true;
                }
            }
            if (!params.containsKey("glass_block") && !params.containsKey("window_block")) {
                params.put("glass_block", glazing);
                changed = true;
            }
            params.put("window_material_explicit", true);
        }
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        if (features.stream().noneMatch(f -> f != null && (f.toLowerCase(Locale.ROOT).contains("slit")
                || f.toLowerCase(Locale.ROOT).contains("iron_bars")))) {
            features.add("long_slit_windows");
            changed = true;
        }
        if (!changed) return c;
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), features, params);
    }

    private static Component repairRoof(Component c, String corpus) {
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        boolean changed = false;
        String id = String.valueOf(params.getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
        Object rt = params.get("roof_type");
        if (rt == null) rt = params.get("roofType");
        String roof = rt == null ? "" : String.valueOf(rt).toLowerCase(Locale.ROOT);
        boolean looksSpire = id.contains("spire") || id.contains("crown") || id.contains("cap")
                || (c.features() != null && c.features().stream().anyMatch(f -> f != null
                && (f.toLowerCase(Locale.ROOT).contains("spire")
                || f.toLowerCase(Locale.ROOT).contains("尖"))));
        if (looksSpire || corpus.contains("尖顶") || corpus.contains("spire")) {
            if (roof.isBlank() || "flat".equals(roof) || "gable".equals(roof) || "hip".equals(roof)) {
                params.put("roof_type", "pyramid");
                changed = true;
            }
            Object rise = params.get("roof_rise");
            if (rise == null) rise = params.get("roofRise");
            int riseVal = 0;
            if (rise instanceof Number n) riseVal = n.intValue();
            else if (rise != null) {
                try { riseVal = Integer.parseInt(String.valueOf(rise).trim()); } catch (NumberFormatException ignored) {}
            }
            if (riseVal < 6) {
                params.put("roof_rise", Math.max(6, c.dimensions() == null ? 8 : Math.max(6, c.dimensions().height())));
                changed = true;
            }
            List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
            if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("spire"))) {
                features.add("steep_pyramidal_spire");
                changed = true;
            }
            if (!changed) return c;
            return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), features, params);
        }
        return c;
    }

    private static Component repairEntrance(Component c, Component mass, LlmPlan plan) {
        if (mass == null || mass.dimensions() == null || mass.relativePosition() == null
                || c.relativePosition() == null || c.dimensions() == null) {
            return c;
        }
        // Drop authored facing so alignEntranceToMass can center on the slot face.
        // Also snap X/Z to the mass mid-face immediately for preview fidelity.
        Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
        Vec3i massOrigin = mass.relativePosition();
        Dimensions md = mass.dimensions();
        Dimensions ed = c.dimensions();
        String facing = String.valueOf(params.getOrDefault("facing",
                plan.globalConstraints() != null && plan.globalConstraints().facing() != null
                        ? plan.globalConstraints().facing().name() : "SOUTH")).toUpperCase(Locale.ROOT);
        int doorW = Math.max(ed.width(), ed.depth());
        int x = c.relativePosition().x();
        int y = c.relativePosition().y();
        int z = c.relativePosition().z();
        boolean changed = false;
        switch (facing) {
            case "NORTH", "SOUTH" -> {
                int centered = massOrigin.x() + Math.max(0, (md.width() - Math.max(1, ed.width())) / 2);
                if (x != centered) {
                    x = centered;
                    changed = true;
                }
                z = "NORTH".equals(facing) ? massOrigin.z() : massOrigin.z() + md.depth() - 1;
            }
            case "EAST", "WEST" -> {
                int centered = massOrigin.z() + Math.max(0, (md.depth() - Math.max(1, ed.depth())) / 2);
                if (z != centered) {
                    z = centered;
                    changed = true;
                }
                x = "WEST".equals(facing) ? massOrigin.x() : massOrigin.x() + md.width() - 1;
            }
            default -> {}
        }
        if (y < massOrigin.y()) {
            y = massOrigin.y();
            changed = true;
        }
        params.put("facing", facing);
        if (doorW < 2) {
            params.put("door_width", 2);
            changed = true;
        }
        if (!changed && x == c.relativePosition().x() && z == c.relativePosition().z()) {
            return c;
        }
        return new Component(c.componentType(), c.slotId(), new Vec3i(x, y, z),
                c.dimensions(), c.features(), params);
    }

    private static Component findPrimaryTowerMass(List<Component> components) {
        Component best = null;
        int bestH = -1;
        for (Component c : components) {
            if (c == null || c.componentType() == null || c.dimensions() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!(type.startsWith("MASS") || "MAIN_MASS".equals(type) || type.startsWith("TOWER"))) continue;
            int h = c.dimensions().height();
            if (h > bestH) {
                bestH = h;
                best = c;
            }
        }
        return best;
    }

    private static String resolveIronBarsGlazing(LlmPlan plan) {
        StyleAttributes attrs = plan.styleAttributes();
        if (attrs != null) {
            String from = attrs.resolvePaletteBlock("glazing", "window", "glass", "window_material");
            if (from != null && (from.contains("bars") || from.contains("fence"))) {
                return from.contains(":") ? from : "minecraft:" + from;
            }
        }
        String corpus = requestCorpus(plan);
        if (corpus.contains("铁栏") || corpus.contains("iron_bars") || corpus.contains("狭窗")
                || corpus.contains("iron bar")) {
            return "minecraft:iron_bars";
        }
        if (plan.styleProfile() != null && plan.styleProfile().toLowerCase(Locale.ROOT).contains("gothic")) {
            return "minecraft:iron_bars";
        }
        return "minecraft:iron_bars";
    }

    private static String resolveAccent(LlmPlan plan) {
        StyleAttributes attrs = plan.styleAttributes();
        if (attrs != null) {
            String from = attrs.resolvePaletteBlock("accent", "accent_wall", "trim", "secondary");
            if (from != null && !from.isBlank()) {
                return from.contains(":") ? from : "minecraft:" + from;
            }
            if (attrs.accentMaterial() != null && !attrs.accentMaterial().isBlank()) {
                String a = attrs.accentMaterial();
                return a.contains(":") ? a : "minecraft:" + a;
            }
        }
        return "minecraft:andesite";
    }

    private static boolean looksGothicBellTower(LlmPlan plan, String corpus) {
        boolean gothic = containsAny(corpus, GOTHIC_CUES)
                || (plan.styleProfile() != null
                && plan.styleProfile().toLowerCase(Locale.ROOT).contains("gothic"));
        if (!gothic) return false;
        boolean tower = corpus.contains("钟楼") || corpus.contains("bell") || corpus.contains("tower")
                || corpus.contains("塔") || corpus.contains("clock");
        if (tower) return true;
        // Tall narrow mass (≥3× footprint height) under gothic style.
        for (Component c : plan.components()) {
            if (c == null || c.dimensions() == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!(type.startsWith("MASS") || type.startsWith("TOWER"))) continue;
            int fp = Math.max(c.dimensions().width(), c.dimensions().depth());
            if (fp > 0 && c.dimensions().height() >= Math.max(20, fp * 2)) return true;
        }
        return false;
    }

    private static String requestCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.playerFidelityNoticeZh() != null) sb.append(' ').append(plan.playerFidelityNoticeZh());
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(' ').append(f);
            }
        }
        if (plan.styleProfile() != null) sb.append(' ').append(plan.styleProfile());
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent_text");
            if (ri != null) sb.append(' ').append(ri);
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> map) {
                Object ot = map.get("original_text");
                if (ot != null) sb.append(' ').append(ot);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String corpus, List<String> cues) {
        if (corpus == null || corpus.isBlank()) return false;
        for (String cue : cues) {
            if (cue != null && corpus.contains(cue.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
