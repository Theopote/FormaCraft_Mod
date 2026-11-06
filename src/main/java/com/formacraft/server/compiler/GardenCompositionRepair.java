package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Jiangnan / Japanese garden plans often emit every amenity as an 8×6×8 solid cube
 * stacked at the same relative origin. Clamp foundations / cornices, force enclosure
 * walls, thin paths, and fan out co-located garden décor so courtyards read as gardens
 * instead of overlapping boxes.
 */
final class GardenCompositionRepair {
    private GardenCompositionRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        if (!looksGarden(plan)) return plan;

        Map<String, Slot> slots = new HashMap<>();
        if (plan.layout() != null && plan.layout().slots() != null) {
            for (Slot s : plan.layout().slots()) {
                if (s != null && s.slotId() != null) slots.put(s.slotId(), s);
            }
        }

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        int fanIndex = 0;
        for (Component c : plan.components()) {
            if (c == null) {
                out.add(null);
                continue;
            }
            Component next = repairOne(c, slots);
            if (next != c) changed = true;
            // Fan décor / rockery / bridge that share (0,0,0) on the garden slot.
            if (shouldFan(next) && next.relativePosition() != null
                    && next.relativePosition().x() == 0 && next.relativePosition().z() == 0) {
                int ox = (fanIndex % 3) * 8 - 8;
                int oz = (fanIndex / 3) * 8 - 8;
                if (ox != 0 || oz != 0) {
                    next = withPosition(next, new Vec3i(ox, next.relativePosition().y(), oz));
                    changed = true;
                    FormacraftMod.LOGGER.info(
                            "GardenCompositionRepair: fan {} → ({}, {})",
                            idOf(next), ox, oz);
                }
                fanIndex++;
            }
            out.add(next);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repairOne(Component c, Map<String, Slot> slots) {
        String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        Dimensions d = c.dimensions();
        if (d == null) return c;

        if ("FOUNDATION".equals(type) && d.height() > 2 && looksPlinth(c)) {
            FormacraftMod.LOGGER.info(
                    "GardenCompositionRepair: clamp foundation height {}→1 id={}",
                    d.height(), idOf(c));
            return withDims(c, new Dimensions(d.width(), d.depth(), 1));
        }
        if ("DECOR_DETAIL".equals(type) && d.height() >= 6 && looksCornice(c)) {
            FormacraftMod.LOGGER.info(
                    "GardenCompositionRepair: clamp cornice height {}→1 id={}",
                    d.height(), idOf(c));
            return withDims(c, new Dimensions(d.width(), d.depth(), 1));
        }
        if ("WALL".equals(type)) {
            Map<String, Object> params = new HashMap<>();
            if (c.params() != null) params.putAll(c.params());
            if (!"curtain_wall".equals(String.valueOf(params.get("wall_type")))) {
                params.put("wall_type", "curtain_wall");
                params.putIfAbsent("thickness", 1);
            }
            int w = d.width();
            int dep = d.depth();
            int h = d.height() > 4 ? 3 : Math.max(2, d.height());
            // Prefer the garden slot envelope when the wall is a tiny 8×6 blob.
            Slot slot = c.slotId() == null ? null : slots.get(c.slotId());
            if (slot != null && slot.size() != null
                    && slot.size().x() >= 16 && slot.size().z() >= 16) {
                w = Math.max(w, slot.size().x());
                dep = Math.max(dep, slot.size().z());
            } else if (w <= 12 && dep <= 12) {
                // Orchestrator often omits slot.size; still give the garden a readable enclosure.
                w = Math.max(w, 24);
                dep = Math.max(dep, 20);
            }
            if (w != d.width() || dep != d.depth() || h != d.height()
                    || !"curtain_wall".equals(String.valueOf(
                    c.params() == null ? null : c.params().get("wall_type")))) {
                FormacraftMod.LOGGER.info(
                        "GardenCompositionRepair: enclosure wall {} → {}x{}x{}",
                        idOf(c), w, dep, h);
                return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                        new Dimensions(w, dep, h), c.features(), params);
            }
            return c;
        }
        if ("PATH".equals(type) && d.width() >= 6 && d.depth() >= 6) {
            // Collapse square path pads into a thin north-south walkway.
            FormacraftMod.LOGGER.info("GardenCompositionRepair: thin path id={}", idOf(c));
            return withDims(c, new Dimensions(2, Math.max(d.depth(), d.width()), 1));
        }
        if (("MASS_ANCILLARY".equals(type) || "MASS_MAIN".equals(type))
                && looksPavilion(c) && d.height() >= 7 && d.width() <= 10) {
            // Pavilion blobs matching hall height look like duplicate houses.
            return withDims(c, new Dimensions(d.width(), d.depth(), Math.min(5, d.height())));
        }
        return c;
    }

    private static boolean shouldFan(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().toUpperCase(Locale.ROOT);
        if (!("DECOR_DETAIL".equals(t) || "TERRACE".equals(t) || "COURTYARD".equals(t)
                || "COURTYARD_SPACE".equals(t) || "PATH".equals(t))) {
            return false;
        }
        String id = idOf(c).toLowerCase(Locale.ROOT);
        return id.contains("rock") || id.contains("bridge") || id.contains("terrace")
                || id.contains("path") || id.contains("court") || id.contains("pond")
                || looksFeature(c, "rockery", "bridge", "water", "path", "terrace");
    }

    private static boolean looksGarden(LlmPlan plan) {
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && looksGardenToken(f)) return true;
            }
        }
        if (plan.playerFidelityNoticeZh() != null && looksGardenToken(plan.playerFidelityNoticeZh())) {
            return true;
        }
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        if (style.contains("garden") || style.contains("jiangnan") || style.contains("japanese")) {
            return true;
        }
        if (plan.planMeta() != null) {
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> map) {
                Object bc = map.get("building_class");
                if (bc != null && "garden".equalsIgnoreCase(String.valueOf(bc))) return true;
                Object text = map.get("original_text");
                if (text != null && looksGardenToken(String.valueOf(text))) return true;
            }
        }
        return false;
    }

    private static boolean looksGardenToken(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("garden") || lower.contains("园林") || lower.contains("庭院")
                || lower.contains("jiangnan") || lower.contains("japanese") || lower.contains("日式")
                || lower.contains("courtyard compound");
    }

    private static boolean looksPlinth(Component c) {
        return looksFeature(c, "plinth", "foundation", "基座", "台基")
                || "plinth".equalsIgnoreCase(String.valueOf(
                c.params() == null ? "" : c.params().getOrDefault("foundation_type", "")));
    }

    private static boolean looksCornice(Component c) {
        String id = idOf(c).toLowerCase(Locale.ROOT);
        return id.contains("cornice") || looksFeature(c, "cornice", "ornament_band", "eave_trim");
    }

    private static boolean looksPavilion(Component c) {
        String id = idOf(c).toLowerCase(Locale.ROOT);
        return id.contains("pavilion") || id.contains("亭") || looksFeature(c, "pavilion", "open_pavilion", "亭");
    }

    private static boolean looksFeature(Component c, String... tokens) {
        if (c.features() == null) return false;
        for (String f : c.features()) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            for (String t : tokens) {
                if (lower.contains(t.toLowerCase(Locale.ROOT)) || f.contains(t)) return true;
            }
        }
        return false;
    }

    private static String idOf(Component c) {
        if (c.params() == null) return String.valueOf(c.componentType());
        Object id = c.params().get("component_id");
        return id == null ? String.valueOf(c.componentType()) : String.valueOf(id);
    }

    private static Component withDims(Component c, Dimensions d) {
        return new Component(c.componentType(), c.slotId(), c.relativePosition(), d, c.features(), c.params());
    }

    private static Component withPosition(Component c, Vec3i rp) {
        return new Component(c.componentType(), c.slotId(), rp, c.dimensions(), c.features(), c.params());
    }
}
