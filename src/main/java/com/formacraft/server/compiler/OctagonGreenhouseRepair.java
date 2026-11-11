package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Square regular-octagon greenhouse AABB + shared centre for hosted satellites. */
final class OctagonGreenhouseRepair {
    private OctagonGreenhouseRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        if (!looksOctagonGreenhouse(plan)) return plan;
        Component main = findGreenhouseMass(plan);
        if (main == null || main.dimensions() == null || main.params() == null) return plan;
        int diameter = resolveDiameter(main, plan);
        if (diameter < 7) return plan;
        String mainId = String.valueOf(main.params().get("component_id"));
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = repairComponent(c, mainId, diameter, main);
            if (next != c) changed = true;
            out.add(next);
        }
        if (!changed) return plan;
        FormacraftMod.LOGGER.info("OctagonGreenhouseRepair: sync square octagon diameter={} host={}", diameter, mainId);
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static Component repairComponent(Component c, String mainId, int diameter, Component main) {
        if (c == null || c.params() == null || c.dimensions() == null) return c;
        String id = String.valueOf(c.params().getOrDefault("component_id", ""));
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        boolean isMain = mainId.equals(id);
        boolean hosted = host != null && mainId.equals(String.valueOf(host));
        if (!isMain && !hosted) return c;
        Map<String, Object> params = new HashMap<>(c.params());
        boolean changed = false;
        Object facade = params.get("facade_profile");
        if (facade != null && String.valueOf(facade).toLowerCase(Locale.ROOT).contains("pilaster")) {
            params.put("facade_profile", "mullion_grid");
            changed = true;
        }
        params.put("shape", "octagon");
        params.put("sides", 8);
        if (params.containsKey("plan_type") || params.containsKey("planType")) {
            params.put("plan_type", "none");
            changed = true;
        }
        int radius = Math.max(1, diameter / 2);
        Object prevR = params.get("radius");
        if (!(prevR instanceof Number n && n.intValue() == radius)) {
            params.put("radius", radius);
            changed = true;
        }
        Dimensions dims = c.dimensions();
        int targetW = diameter;
        int targetD = diameter;
        String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
        if ("FACADE_WINDOWS".equals(type) || "FACADE".equals(type) || "ENTRANCE".equals(type)) {
            targetW = dims.width();
            targetD = dims.depth();
        } else if ("ROOF".equals(type) || "FOUNDATION".equals(type)) {
            int oversail = Math.min(2, Math.max(0, Math.max(dims.width(), dims.depth())
                    - Math.max(main.dimensions().width(), main.dimensions().depth())));
            targetW = diameter + oversail * 2;
            targetD = diameter + oversail * 2;
        } else if ("DECOR_DETAIL".equals(type) || "BALCONY".equals(type) || "MASS_SECONDARY".equals(type)) {
            Object kind = params.getOrDefault("kind", params.get("detail_type"));
            String kindText = kind == null ? id.toLowerCase(Locale.ROOT) : String.valueOf(kind).toLowerCase(Locale.ROOT);
            if (kindText.contains("pool") || kindText.contains("stair") || kindText.contains("spiral")) {
                targetW = dims.width();
                targetD = dims.depth();
            }
        }
        Vec3i pos = c.relativePosition() == null ? new Vec3i(0, 0, 0) : c.relativePosition();
        boolean wantCenter = isMain || "MASS_SECONDARY".equals(type) || "BALCONY".equals(type)
                || "FOUNDATION".equals(type) || id.contains("column") || id.contains("frame")
                || id.contains("pool") || id.contains("walkway") || id.contains("l2");
        if (wantCenter) {
            if (!"center".equalsIgnoreCase(String.valueOf(params.getOrDefault("anchor_mode", "")))) {
                params.put("anchor_mode", "center");
                changed = true;
            }
            if (pos.x() != 0 || pos.z() != 0) {
                pos = new Vec3i(0, pos.y(), 0);
                changed = true;
            }
        } else {
            int half = diameter / 2;
            if (Math.abs(pos.x() + dims.width() / 2) > half + 2
                    || Math.abs(pos.z() + dims.depth() / 2) > half + 2
                    || dims.width() != targetW || dims.depth() != targetD) {
                pos = new Vec3i(-(targetW / 2), pos.y(), -(targetD / 2));
                params.put("anchor_mode", "min_corner");
                changed = true;
            }
        }
        if (dims.width() != targetW || dims.depth() != targetD) {
            dims = new Dimensions(targetW, targetD, dims.height());
            changed = true;
        }
        if (!changed) return c;
        return new Component(c.componentType(), c.slotId(), pos, dims, c.features(), params);
    }

    private static int resolveDiameter(Component main, LlmPlan plan) {
        Map<String, Object> params = main.params() == null ? Map.of() : main.params();
        int radius = intParam(params, "radius");
        if (radius <= 0) radius = intParam(params, "outer_radius");
        if (radius > 0) return Math.max(7, (radius * 2) | 1);
        Matcher m = Pattern.compile("边长\\s*[=为:]?\\s*(\\d{1,2})|side\\s*length\\s*[=:]?\\s*(\\d{1,2})")
                .matcher(intentCorpus(plan));
        if (m.find()) {
            try {
                int side = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
                if (side >= 3) {
                    int approx = (int) Math.round(side * (1.0 + Math.sqrt(2.0)));
                    return Math.max(7, approx | 1);
                }
            } catch (NumberFormatException ignored) {}
        }
        int square = Math.max(main.dimensions().width(), main.dimensions().depth());
        if (main.dimensions().width() != main.dimensions().depth()) square = Math.max(square, square + 2);
        return Math.max(7, square | 1);
    }

    private static Component findGreenhouseMass(LlmPlan plan) {
        Component fallback = null;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (!type.startsWith("MASS")) continue;
            String id = String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            if (id.contains("gh") || id.contains("greenhouse") || id.contains("温室") || looksGreenhouseToken(c)) return c;
            if (fallback == null && ("MASS_MAIN".equals(type) || "MAIN_MASS".equals(type))) fallback = c;
        }
        return fallback;
    }

    private static boolean looksOctagonGreenhouse(LlmPlan plan) {
        String corpus = intentCorpus(plan);
        boolean greenhouse = corpus.contains("greenhouse") || corpus.contains("温室")
                || corpus.contains("glasshouse") || corpus.contains("orangerie")
                || corpus.contains("花房") || corpus.contains("玻璃房");
        boolean octagon = corpus.contains("octagon") || corpus.contains("八角") || corpus.contains("8gon");
        if (greenhouse && octagon) return true;
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (looksGreenhouseToken(c) && (looksOctagonToken(c) || octagon)) return true;
        }
        return false;
    }

    private static boolean looksGreenhouseToken(Component c) {
        String id = c.params() == null ? "" : String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
        if (id.contains("greenhouse") || id.contains("gh_") || id.contains("温室") || id.contains("glasshouse") || id.equals("gh_body")) return true;
        Object wall = c.params() == null ? null : c.params().get("wall_block");
        if (wall != null && String.valueOf(wall).toLowerCase(Locale.ROOT).contains("glass")) return true;
        if (c.params() != null && c.params().containsKey("glazing_ratio")) return true;
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("greenhouse") || lower.contains("温室") || lower.contains("玻璃")
                        || lower.contains("glazing") || lower.contains("glass")) return true;
            }
        }
        return false;
    }

    private static boolean looksOctagonToken(Component c) {
        if (c.params() != null) {
            for (String key : List.of("shape", "footprint_shape", "plan_type", "planType", "component_id")) {
                Object v = c.params().get(key);
                if (v != null) {
                    String lower = String.valueOf(v).toLowerCase(Locale.ROOT);
                    if (lower.contains("octagon") || lower.contains("八角")) return true;
                }
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && (f.toLowerCase(Locale.ROOT).contains("octagon") || f.contains("八角"))) return true;
            }
        }
        return false;
    }

    private static int intParam(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.distinguishingFeatures() != null) for (String f : plan.distinguishingFeatures()) if (f != null) sb.append(f).append(' ');
        if (plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(text).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?,?> map && map.get("original_text") != null) sb.append(map.get("original_text")).append(' ');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
