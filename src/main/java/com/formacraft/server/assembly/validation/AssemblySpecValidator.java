package com.formacraft.server.assembly.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P0 Validator for extra.assembly.
 *
 * Goal:
 * - Make LLM output stable by catching common schema/type mistakes early.
 * - Provide actionable error messages with paths.
 *
 * Non-goals (for now):
 * - Perfect type checking for every op or semantic key.
 * - Deep geometric validity checks.
 */
public final class AssemblySpecValidator {
    private AssemblySpecValidator() {}

    public static List<AssemblyValidationIssue> validate(Object assemblyObj) {
        List<AssemblyValidationIssue> out = new ArrayList<>();
        if (!(assemblyObj instanceof Map<?, ?> root)) {
            out.add(err("$", "E_ROOT_TYPE", "extra.assembly 必须是 JSON 对象（map）"));
            return out;
        }

        // Root unknown-key warnings (training-friendly)
        warnUnknownKeys(out, "$", root, Set.of(
                "paletteId", "entranceFacing",
                "ops",
                "graph", "components", "connections"
        ));

        // ops form
        Object opsObj = root.get("ops");
        if (opsObj instanceof List<?> opsList) {
            validateOps(out, "$.ops", opsList);
        }

        // graph/components form
        Object graphObj = root.get("graph");
        Map<?, ?> graph = (graphObj instanceof Map<?, ?> gm) ? gm : null;
        if (graph != null) {
            warnUnknownKeys(out, "$.graph", graph, Set.of("components", "connections"));
        }
        Object compsObj = (graph != null) ? graph.get("components") : root.get("components");
        Object connsObj = (graph != null) ? graph.get("connections") : root.get("connections");

        Set<String> componentIds = new HashSet<>();
        if (compsObj instanceof List<?> comps) {
            validateComponents(out, graph != null ? "$.graph.components" : "$.components", comps, componentIds);
        } else if (opsObj == null) {
            // If neither ops nor components exist, that's almost certainly wrong.
            out.add(err("$", "E_MISSING_OPS_OR_COMPONENTS", "extra.assembly 缺少 ops 或 graph.components/components"));
        }

        if (connsObj instanceof List<?> conns) {
            validateConnections(out, graph != null ? "$.graph.connections" : "$.connections", conns, componentIds);
        }

        return out;
    }

    // ---------------- ops ----------------

    private static void validateOps(List<AssemblyValidationIssue> out, String path, List<?> ops) {
        for (int i = 0; i < ops.size(); i++) {
            Object it = ops.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> m)) {
                out.add(err(p, "E_OP_NOT_OBJECT", "op 必须是对象（map）"));
                continue;
            }
            warnUnknownKeys(out, p, m, Set.of(
                    "op",
                    "at", "x", "y", "z", "dx", "dy", "dz",
                    // common bounds
                    "x0", "x1", "y0", "y1", "z0", "z1",
                    // materials
                    "material", "wall", "roof", "slab", "floor", "window", "fill", "frame", "accent", "air",
                    // generic knobs
                    "type", "kind", "face", "faces",
                    "w", "d", "h", "width", "depth", "height",
                    // spline
                    "points", "profile", "profileFrame", "profileSnap", "frame", "snap",
                    "profileW", "profileH", "profilePoints", "profileRings", "rings",
                    "profileScale0", "profileScale1", "scale0", "scale1",
                    "profileW0", "profileW1", "profileH0", "profileH1",
                    "twistTurns", "twistPhase",
                    "capEnds", "capThickness", "carveInterior",
                    "connectSamples", "connectMaxStep",
                    "r", "radius", "r0", "r1", "radius0", "radius1",
                    "hollow", "thickness", "samplesPerBlock",
                    // openings
                    "rows", "cols", "winW", "winH", "sillY", "marginX", "marginY", "gapX", "gapY", "frameThickness", "mullionStep",
                    "doorW", "doorH",
                    "archType", "arch", "archThickness", "archT",
                    "keystone", "keystoneOn",
                    "tracery", "traceryType", "traceryMaterial", "traceryThickness", "traceryT", "traceryY", "traceryInset",
                    "foilRadius", "foilCenterY", "foilCount", "foilStepY", "foilGapY",
                    "traceryFoilRadius", "traceryFoilCenterY", "traceryFoilCount", "traceryFoilStepY",
                    "r", "radius", "centerY", "petals", "spokes", "ring", "phase", "phi", "spokeWidth", "spokeW", "spokeThreshold", "spokeThresh",
                    "innerFill", "spokeMaterial",
                    // facade grid
                    "bayW", "bayH", "moduleW", "moduleH", "gridW", "gridH",
                    "mullionThickness", "mullionT", "transomThickness", "transomT", "borderThickness", "borderT",
                    "marginU", "inset", "depth",
                    "spandrelEvery", "spEvery", "spandrelHeight", "spH", "spandrelOffset", "spOffset", "spandrelFill",
                    // surface bands
                    "horizontalBands", "hBands", "bandsH",
                    "verticalBands", "vBands", "bandsV"
            ));
            String op = str(m.get("op"), "").trim().toUpperCase(Locale.ROOT);
            if (op.isBlank()) out.add(err(p + ".op", "E_OP_MISSING", "缺少 op 字段"));

            // P0 per-op required fields
            if (op.contains("SPLINE")) {
                Object pts = m.get("points");
                if (!(pts instanceof List<?> l) || l.size() < 2) {
                    out.add(err(p + ".points", "E_SPLINE_POINTS_MIN2", "SPLINE_SWEEP.points 至少需要 2 个点"));
                } else {
                    validatePoints(out, p + ".points", l);
                }
            }
            if (op.equals("SURFACE_PATTERN")) {
                requireFace(out, p, m);
                String pattern = str(m.get("pattern"), str(m.get("type"), "GRID")).trim().toUpperCase(Locale.ROOT);
                if (!(pattern.equals("GRID") || pattern.equals("STRIPES_V") || pattern.equals("STRIPES_H") || pattern.equals("RIBS_V") || pattern.equals("RIBS_H")
                        || pattern.equals("STRIPES_VERTICAL") || pattern.equals("STRIPES_HORIZONTAL") || pattern.equals("RIBS_VERTICAL") || pattern.equals("RIBS_HORIZONTAL"))) {
                    out.add(err(p + ".pattern", "E_PATTERN_VALUE", "SURFACE_PATTERN.pattern 取值非法: " + pattern));
                }
                requireIntMin(out, p, m, "step", 1);
                requireIntMin(out, p, m, "thickness", 1);
            }
            if (op.equals("FACADE_GRID")) {
                requireFace(out, p, m);
                requireIntMin(out, p, m, "bayW", 1);
                requireIntMin(out, p, m, "bayH", 1);
            }
            if (op.equals("SURFACE_BANDS")) {
                requireFace(out, p, m);
            }
            if (op.equals("OPENINGS")) {
                requireFace(out, p, m);
                String kind = str(m.get("kind"), str(m.get("type"), "")).trim().toUpperCase(Locale.ROOT);
                if (kind.isBlank()) {
                    out.add(err(p + ".kind", "E_OPENINGS_KIND_MISSING", "OPENINGS.kind 缺失"));
                } else {
                    boolean ok = kind.equals("WINDOW_GRID") || kind.equals("DOOR") || kind.equals("ARCH_WINDOW") || kind.equals("ROSE_WINDOW");
                    if (!ok) out.add(err(p + ".kind", "E_OPENINGS_KIND_VALUE", "OPENINGS.kind 取值非法: " + kind));
                }
                // Range sanity (soft): allow missing fields (engine has defaults), but validate if present.
                requireIntMin(out, p, m, "rows", 1);
                requireIntMin(out, p, m, "cols", 1);
                requireIntMin(out, p, m, "winW", 1);
                requireIntMin(out, p, m, "winH", 1);
                if (m.get("doorW") != null) requireIntMin(out, p, m, "doorW", 1);
                if (m.get("doorH") != null) requireIntMin(out, p, m, "doorH", 2);
                if (m.get("r") != null || m.get("radius") != null) {
                    // rose window uses r>=2
                    Integer rv = intOrNull(m.get("r"));
                    if (rv == null) rv = intOrNull(m.get("radius"));
                    if (rv != null && rv < 2) out.add(err(p + ".r", "E_ROSE_R_RANGE", "ROSE_WINDOW.r 必须 >= 2"));
                }
                if (m.get("petals") != null || m.get("spokes") != null) {
                    Integer pv = intOrNull(m.get("petals"));
                    if (pv == null) pv = intOrNull(m.get("spokes"));
                    if (pv != null && pv < 3) out.add(err(p + ".petals", "E_ROSE_PETALS_RANGE", "ROSE_WINDOW.petals 必须 >= 3"));
                }
                if (m.get("archType") != null || m.get("arch") != null) {
                    String at = str(m.get("archType"), str(m.get("arch"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!at.isEmpty() && !(at.equals("ROUND") || at.equals("POINTED"))) {
                        out.add(err(p + ".archType", "E_ARCH_TYPE_VALUE", "ARCH_WINDOW.archType 取值非法: " + at + "（允许 ROUND/POINTED）"));
                    }
                }
            }
        }
    }

    // ---------------- components ----------------

    private static void validateComponents(List<AssemblyValidationIssue> out, String path, List<?> comps, Set<String> idsOut) {
        for (int i = 0; i < comps.size(); i++) {
            Object it = comps.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> c)) {
                out.add(err(p, "E_COMPONENT_NOT_OBJECT", "component 必须是对象（map）"));
                continue;
            }
            warnUnknownKeys(out, p, c, Set.of(
                    "id", "type", "op",
                    "at", "x", "y", "z",
                    "w", "d", "h", "width", "depth", "height",
                    "r", "radius",
                    "points",
                    "profile", "profileFrame", "profileSnap", "frame", "snap",
                    "profileW", "profileH",
                    "profilePoints", "profileRings", "rings",
                    "profileScale0", "profileScale1", "scale0", "scale1",
                    "profileW0", "profileW1", "profileH0", "profileH1",
                    "twistTurns", "twistPhase",
                    "capEnds", "capThickness", "carveInterior",
                    "connectSamples", "connectMaxStep",
                    "hollow", "thickness",
                    "material", "wall", "window", "floor", "roof", "slab",
                    "ports",
                    "facade", "facade_logic", "facadeLogic"
            ));
            String id = str(c.get("id"), "").trim();
            if (!id.isEmpty()) {
                if (!idsOut.add(id)) out.add(err(p + ".id", "E_COMPONENT_ID_DUP", "重复的 component id: " + id));
            }

            String type = str(c.get("type"), str(c.get("op"), "")).trim().toUpperCase(Locale.ROOT);
            if (type.isBlank()) {
                out.add(err(p + ".type", "E_COMPONENT_TYPE_MISSING", "缺少 type（或 op）字段"));
                continue;
            }

            // P0: validate known component kinds we heavily use
            if (type.contains("SPLINE")) {
                Object pts = c.get("points");
                if (!(pts instanceof List<?> l) || l.size() < 2) {
                    out.add(err(p + ".points", "E_SPLINE_POINTS_MIN2", "SPLINE_SWEEP.points 至少需要 2 个点"));
                } else {
                    validatePoints(out, p + ".points", l);
                }
            }

            if (type.contains("SHELL_BOX") || type.contains("BOX_SHELL")) {
                requireIntMin(out, p, c, "w", 1);
                requireIntMin(out, p, c, "d", 1);
                requireIntMin(out, p, c, "h", 2);
                validateFacadeMacros(out, p, c);
            }
        }
    }

    private static void validateFacadeMacros(List<AssemblyValidationIssue> out, String compPath, Map<?, ?> comp) {
        Object facadeObj = comp.get("facade");
        if (facadeObj == null) facadeObj = comp.get("facade_logic");
        if (facadeObj == null) facadeObj = comp.get("facadeLogic");
        if (!(facadeObj instanceof Map<?, ?> facade)) return;

        // facadeGrid
        Object fg = facade.get("facadeGrid");
        if (fg == null) fg = facade.get("FACADE_GRID");
        if (fg == null) fg = facade.get("curtainWall");
        if (fg instanceof Map<?, ?> m) {
            String p = compPath + ".facade.facadeGrid";
            // faces optional; default ALL, but validate if present
            if (m.get("face") != null || m.get("faces") != null) requireFacesString(out, p, m);
            requireIntMin(out, p, m, "bayW", 1);
            requireIntMin(out, p, m, "bayH", 1);
            // spandrel optional
            if (m.get("spandrelEvery") != null || m.get("spEvery") != null) requireIntMin(out, p, m, "spandrelEvery", 0);
            if (m.get("spandrelHeight") != null || m.get("spH") != null) requireIntMin(out, p, m, "spandrelHeight", 0);
        }

        // surfaceBands
        Object sb = facade.get("surfaceBands");
        if (sb == null) sb = facade.get("SURFACE_BANDS");
        if (sb == null) sb = facade.get("bands");
        if (sb instanceof Map<?, ?> m) {
            String p = compPath + ".facade.surfaceBands";
            if (m.get("face") != null || m.get("faces") != null) requireFacesString(out, p, m);
            Object hb = m.get("horizontalBands");
            if (hb == null) hb = m.get("hBands");
            if (hb == null) hb = m.get("bandsH");
            if (hb != null && !(hb instanceof List<?>)) out.add(err(p + ".horizontalBands", "E_BANDS_H_NOT_LIST", "horizontalBands 必须是数组"));

            Object vb = m.get("verticalBands");
            if (vb == null) vb = m.get("vBands");
            if (vb == null) vb = m.get("bandsV");
            if (vb != null && !(vb instanceof List<?>)) out.add(err(p + ".verticalBands", "E_BANDS_V_NOT_LIST", "verticalBands 必须是数组"));
        }
    }

    // ---------------- connections ----------------

    private static void validateConnections(List<AssemblyValidationIssue> out, String path, List<?> conns, Set<String> componentIds) {
        for (int i = 0; i < conns.size(); i++) {
            Object it = conns.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> c)) {
                out.add(err(p, "E_CONN_NOT_OBJECT", "connection 必须是对象（map）"));
                continue;
            }
            warnUnknownKeys(out, p, c, Set.of(
                    "type",
                    "from", "to",
                    "via",
                    "avoid", "avoidComponents", "avoidAllComponents", "avoidMargin", "avoidExcludeComponents", "avoidIncludeComponents",
                    "routing", "avoidAStar",
                    "routingPad", "routingMaxArea", "routingMaxNodes",
                    "routingPreferStraight", "routingPreferAxis", "routingPreferAxisWeight", "routingPreferDoorAxis",
                    "routingLeadOut", "routingLeadIn", "routingAutoLead", "routingLeadSoft", "routingLeadHard",
                    "routingLeadWeight", "routingLeadOutWeight", "routingLeadInWeight", "routingLeadRing", "routingLeadInStepsMaxNodes",
                    "routingStyle", "routingStyleStrength", "routingQoS",
                    "width", "wallHeight", "wallThickness", "foundationDepth", "maxStep",
                    "material",
                    "terrainAdaptation"
            ));
            validateEndpointRef(out, p + ".from", c.get("from"), componentIds);
            validateEndpointRef(out, p + ".to", c.get("to"), componentIds);
        }
    }

    private static void validateEndpointRef(List<AssemblyValidationIssue> out, String path, Object v, Set<String> ids) {
        if (v == null) {
            out.add(err(path, "E_ENDPOINT_MISSING", "缺少端点"));
            return;
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) { out.add(err(path, "E_ENDPOINT_EMPTY", "端点字符串不能为空")); return; }
            int dot = t.indexOf('.');
            if (dot > 0) {
                String id = t.substring(0, dot).trim();
                if (!id.isEmpty() && !ids.isEmpty() && !ids.contains(id)) {
                    out.add(warn(path, "W_ENDPOINT_UNKNOWN_COMPONENT", "引用了不存在的组件 id: " + id + "（示例/训练建议修正）"));
                }
            }
            return;
        }
        if (v instanceof Map<?, ?> m) {
            Object cid = m.get("component");
            if (cid == null) cid = m.get("id");
            if (cid == null) {
                // could be raw {x,y,z}; accept
                return;
            }
            String id = str(cid, "").trim();
            if (!id.isEmpty() && !ids.isEmpty() && !ids.contains(id)) {
                out.add(warn(path, "W_ENDPOINT_UNKNOWN_COMPONENT", "引用了不存在的组件 id: " + id + "（示例/训练建议修正）"));
            }
            return;
        }
        out.add(err(path, "E_ENDPOINT_BAD_TYPE", "端点必须是字符串 \"A.port\" 或对象 {component/id,port,...} 或坐标 {x,y,z}"));
    }

    // ---------------- helpers ----------------

    private static void validatePoints(List<AssemblyValidationIssue> out, String path, List<?> pts) {
        for (int i = 0; i < pts.size(); i++) {
            Object it = pts.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> m)) {
                out.add(err(p, "E_POINT_NOT_OBJECT", "point 必须是 {x,y,z} 对象"));
                continue;
            }
            if (m.get("x") == null || m.get("y") == null || m.get("z") == null) {
                out.add(err(p, "E_POINT_MISSING_XYZ", "point 必须包含 x/y/z"));
            }
        }
    }

    private static void requireFace(List<AssemblyValidationIssue> out, String base, Map<?, ?> m) {
        if (m.get("face") == null && m.get("faces") == null) {
            out.add(err(base + ".face", "E_FACE_MISSING", "缺少 face/faces"));
        } else {
            requireFacesString(out, base, m);
        }
    }

    private static void requireFacesString(List<AssemblyValidationIssue> out, String base, Map<?, ?> m) {
        Object f = m.get("face");
        if (f == null) f = m.get("faces");
        if (f == null) return;
        if (!(f instanceof String)) {
            out.add(err(base + ".face", "E_FACE_TYPE", "face/faces 必须是字符串（如 ALL 或 NORTH,EAST）"));
            return;
        }
        // validate enum-ish tokens
        String raw = String.valueOf(f).trim();
        if (raw.isEmpty()) {
            out.add(err(base + ".face", "E_FACE_EMPTY", "face/faces 不能为空"));
            return;
        }
        for (String tok : raw.split(",")) {
            String t = tok.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.equals("ALL") || t.equals("*")) continue;
            if (!(t.equals("NORTH") || t.equals("SOUTH") || t.equals("EAST") || t.equals("WEST"))) {
                out.add(err(base + ".face", "E_FACE_VALUE", "face/faces 取值非法: " + tok + "（允许 ALL 或 NORTH/SOUTH/EAST/WEST 或逗号分隔）"));
                break;
            }
        }
    }

    private static void requireIntMin(List<AssemblyValidationIssue> out, String base, Map<?, ?> m, String key, int min) {
        Object v = m.get(key);
        if (v == null) return; // allow defaulting
        Integer x = intOrNull(v);
        if (x == null) {
            out.add(err(base + "." + key, "E_INT_TYPE", "必须是整数"));
            return;
        }
        if (x < min) out.add(err(base + "." + key, "E_INT_RANGE", "必须 >= " + min));
    }

    private static void warnUnknownKeys(List<AssemblyValidationIssue> out, String base, Map<?, ?> m, Set<String> allowed) {
        if (m == null || allowed == null || allowed.isEmpty()) return;
        for (Object k : m.keySet()) {
            if (k == null) continue;
            String key = String.valueOf(k);
            if (allowed.contains(key)) continue;
            // tolerate some common aliases by ignoring case/underscore differences
            String norm = key.replace("-", "_");
            if (allowed.contains(norm)) continue;
            out.add(warn(base + "." + key, "W_UNKNOWN_KEY", "未知字段（可能是拼写错误/幻觉字段）: " + key));
        }
    }

    private static Integer intOrNull(Object v) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s) return Integer.parseInt(s.trim());
        } catch (Exception ignored) {}
        return null;
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static AssemblyValidationIssue err(String path, String code, String msg) {
        return new AssemblyValidationIssue(path, AssemblyValidationIssue.Severity.ERROR, code, msg);
    }

    private static AssemblyValidationIssue warn(String path, String code, String msg) {
        return new AssemblyValidationIssue(path, AssemblyValidationIssue.Severity.WARNING, code, msg);
    }
}


