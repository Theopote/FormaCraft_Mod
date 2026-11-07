package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compositional LLM plans for 四合院 often scatter 正房/厢房/倒座 without a walkable
 * enclosure. Prefer the native {@code courtyard_compound} typology which already
 * places main hall, wings, and gate around a courtyard.
 */
final class SiheyuanCourtyardMisrouteRepair {
    private SiheyuanCourtyardMisrouteRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        if (!looksSiheyuan(plan)) return plan;
        if (alreadyCourtyardCompound(plan)) return plan;
        if (!looksMisroutedComposition(plan)) return plan;

        int[] size = footprintHint(plan);
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "siheyuan_courtyard");
        params.put("typology_id", "courtyard_compound");
        params.put("typology", "courtyard_compound");
        params.put("structural_typology", "courtyard_compound");
        params.put("width", size[0]);
        params.put("depth", size[1]);
        params.put("facing", preserveFacing(plan));
        params.put("includePaths", true);
        params.put("pathWidth", 3);
        params.put("paveCourtyard", true);
        stampMaterials(params, plan.styleAttributes());

        Component structure = new Component(
                "STRUCTURE",
                plan.components().getFirst().slotId(),
                new Vec3i(0, 0, 0),
                new Dimensions(size[0], size[1], Math.max(8, size[1] / 4)),
                List.of("typology:courtyard_compound", "siheyuan", "四合院"),
                params);
        FormacraftMod.LOGGER.info(
                "SiheyuanCourtyardMisrouteRepair: rewrite compositional 四合院 → STRUCTURE courtyard_compound {}x{}",
                size[0], size[1]);
        return PlanComponentRewrite.withComponents(plan, List.of(structure));
    }

    private static boolean looksSiheyuan(LlmPlan plan) {
        String blob = blob(plan);
        if (blob.contains("四合院") || blob.contains("siheyuan") || blob.contains("合院")) return true;
        if (blob.contains("正房") && (blob.contains("厢房") || blob.contains("倒座"))) return true;
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        return style.contains("siheyuan");
    }

    private static boolean alreadyCourtyardCompound(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (!"STRUCTURE".equalsIgnoreCase(c.componentType())) continue;
            if (c.params() != null) {
                for (String key : List.of("typology_id", "typology", "structural_typology")) {
                    Object v = c.params().get(key);
                    if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("courtyard_compound")) {
                        return true;
                    }
                }
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && f.toLowerCase(Locale.ROOT).contains("courtyard_compound")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Compositional plans that lack a clear courtyard enclosure, or that scatter many
     * masses without COURTYARD_SPACE / gate / wing roles, need the typology builder.
     */
    private static boolean looksMisroutedComposition(LlmPlan plan) {
        int masses = 0;
        boolean hasCourt = false;
        boolean hasWing = false;
        boolean hasGate = false;
        boolean hasMain = false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (t.startsWith("MASS") || "TOWER".equals(t) || "GATE".equals(t)) {
                masses++;
                String id = idOf(c).toLowerCase(Locale.ROOT);
                String feat = featuresOf(c).toLowerCase(Locale.ROOT);
                if (id.contains("wing") || id.contains("厢") || feat.contains("wing") || feat.contains("厢")) {
                    hasWing = true;
                }
                if (id.contains("gate") || id.contains("倒座") || id.contains("门楼")
                        || feat.contains("gate") || feat.contains("倒座") || "GATE".equals(t)) {
                    hasGate = true;
                }
                if (id.contains("main") || id.contains("正房") || feat.contains("main_hall")
                        || feat.contains("正房") || "MASS_MAIN".equals(t)) {
                    hasMain = true;
                }
            }
            if (t.contains("COURTYARD") || t.equals("PATH")) {
                hasCourt = true;
            }
        }
        String blob = blob(plan);
        // 三进 (and other multi-jin) layouts are not supported by courtyard_compound's single yard.
        // Never silently demote them — keep the compositional plan (or let validators surface a gap).
        if (blob.contains("三进") || blob.contains("两进") || blob.contains("多进")) {
            return false;
        }
        // Too many free masses is a classic bad 四合院 compositional dump.
        if (masses >= 4 && !(hasCourt && hasWing && hasGate && hasMain)) return true;
        // Incomplete single-jin composition: several masses but no courtyard void / path.
        return masses >= 3 && !hasCourt;
    }

    private static int[] footprintHint(LlmPlan plan) {
        int maxX = 0;
        int maxZ = 0;
        for (Component c : plan.components()) {
            if (c == null || c.dimensions() == null || c.relativePosition() == null) continue;
            String t = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (!(t.startsWith("MASS") || t.contains("COURTYARD") || "GATE".equals(t))) continue;
            int x1 = c.relativePosition().x() + c.dimensions().width();
            int z1 = c.relativePosition().z() + c.dimensions().depth();
            maxX = Math.max(maxX, Math.max(Math.abs(c.relativePosition().x()), Math.abs(x1)));
            maxZ = Math.max(maxZ, Math.max(Math.abs(c.relativePosition().z()), Math.abs(z1)));
        }
        int w = Math.max(28, Math.min(64, maxX * 2 + 4));
        int d = Math.max(32, Math.min(72, maxZ * 2 + 4));
        if (plan.proportionHints() != null) {
            Object cw = plan.proportionHints().get("compound_width");
            Object cd = plan.proportionHints().get("compound_depth");
            if (cw instanceof Number n) w = Math.max(w, n.intValue());
            if (cd instanceof Number n) d = Math.max(d, n.intValue());
        }
        return new int[]{w, d};
    }

    /**
     * Prefer authored facing from global constraints or the first component that names one.
     * Default SOUTH only when nothing was specified (classical 坐北朝南).
     */
    private static String preserveFacing(LlmPlan plan) {
        if (plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
            return plan.globalConstraints().facing().name();
        }
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object raw = c.params().get("facing");
            if (raw == null) raw = c.params().get("orientation");
            if (raw == null) continue;
            String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
            if (s.equals("NORTH") || s.equals("SOUTH") || s.equals("EAST") || s.equals("WEST")) {
                return s;
            }
        }
        String blob = blob(plan);
        if (blob.contains("朝北") || blob.contains("坐南朝北") || blob.contains("facing north")) {
            return "NORTH";
        }
        if (blob.contains("朝东") || blob.contains("facing east")) {
            return "EAST";
        }
        if (blob.contains("朝西") || blob.contains("facing west")) {
            return "WEST";
        }
        return "SOUTH";
    }

    private static void stampMaterials(Map<String, Object> params, StyleAttributes attrs) {
        if (attrs == null) return;
        if (attrs.wallMaterial() != null && !attrs.wallMaterial().isBlank()) {
            params.put("wall_block", attrs.wallMaterial());
        }
        if (attrs.roofMaterial() != null && !attrs.roofMaterial().isBlank()) {
            params.put("roof_block", attrs.roofMaterial());
        }
        if (attrs.floorMaterial() != null && !attrs.floorMaterial().isBlank()) {
            params.put("floor_block", attrs.floorMaterial());
        }
    }

    private static String idOf(Component c) {
        if (c.params() == null) return "";
        Object id = c.params().get("component_id");
        return id == null ? "" : String.valueOf(id);
    }

    private static String featuresOf(Component c) {
        if (c.features() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String f : c.features()) {
            if (f != null) sb.append(f).append(' ');
        }
        return sb.toString();
    }

    private static String blob(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.styleProfile() != null) sb.append(plan.styleProfile()).append(' ');
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(text).append(' ');
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
        }
        if (plan.proportionHints() != null) {
            Object intent = plan.proportionHints().get("architecture_intent");
            if (intent instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
        }
        for (Component c : plan.components()) {
            if (c == null) continue;
            sb.append(featuresOf(c)).append(' ').append(idOf(c)).append(' ');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
