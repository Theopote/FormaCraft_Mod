package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P0 Macro parameter table applier.
 *
 * Reads extra.assembly.macro and maps high-level "gene-like" sliders onto existing assembly knobs.
 *
 * Principles:
 * - Explicit low-level parameters always win (macro only fills missing bits or adds best-effort helpers).
 * - Conservative: do not invent complex geometry; only map to already-supported ops/components.
 */
public final class AssemblyMacroApplier {
    private AssemblyMacroApplier() {}

    public static AssemblyMacroApplyResult apply(Object assemblyObj) {
        List<AssemblyValidationIssue> issues = new ArrayList<>();
        if (!(assemblyObj instanceof Map<?, ?> root)) {
            return new AssemblyMacroApplyResult(assemblyObj, issues);
        }
        Map<String, Object> m = safeMap(root);
        if (m == null) return new AssemblyMacroApplyResult(assemblyObj, issues);

        Object macroObj = m.get("macro");
        if (!(macroObj instanceof Map<?, ?> mm)) {
            return new AssemblyMacroApplyResult(assemblyObj, issues);
        }
        Map<String, Object> macro = safeMap(mm);
        if (macro == null) return new AssemblyMacroApplyResult(m, issues);

        // Resolve graph & components list (support both $.graph.components and $.components)
        Map<String, Object> graph = null;
        Object graphObj = m.get("graph");
        if (graphObj instanceof Map<?, ?> gm) {
            graph = safeMap(gm);
        }
        Object compsObj = (graph != null) ? graph.get("components") : m.get("components");
        Object connsObj = (graph != null) ? graph.get("connections") : m.get("connections");

        // Identify primary component to apply macro
        String targetId = str(macro.get("primaryComponent"), str(macro.get("primaryComponentId"), null));
        if (targetId != null) targetId = targetId.trim();

        Map<String, Object> primary = null;
        if (compsObj instanceof List<?> comps) {
            for (Object it : comps) {
                if (!(it instanceof Map<?, ?> cm)) continue;
                Map<String, Object> c = safeMap(cm);
                if (c == null) continue;
                if (primary == null) primary = c; // fallback to first
                if (targetId != null && targetId.equals(str(c.get("id"), "").trim())) {
                    primary = c;
                    break;
                }
            }
        }

        // Apply shapeType & heightScale to primary component (best-effort).
        if (primary != null) {
            applyShapeType(primary, "$.macro.shapeType", macro, issues);
            applyHeightScale(primary, "$.macro.heightScale", macro, issues);
            applyRoofMacro(m, graph, compsObj, primary, macro, issues);
            applyOpenness(primary, "$.macro.openness", macro, issues);
        }

        // Apply symmetry macro to routing defaults (connections)
        if (connsObj instanceof List<?> conns) {
            applySymmetryToConnections(conns, macro, issues);
        }

        // Bridge tower macro: inject ANCHOR_FOOTPRINT + tower body + saddle rollers
        applyBridgeTower(m, graph, compsObj, macro, issues);

        return new AssemblyMacroApplyResult(m, issues);
    }

    @SuppressWarnings("unchecked")
    private static void applyBridgeTower(Map<String, Object> root,
                                         Map<String, Object> graph,
                                         Object compsObj,
                                         Map<String, Object> macro,
                                         List<AssemblyValidationIssue> issues) {
        Object btObj = macro.get("bridgeTower");
        if (btObj == null) btObj = macro.get("bridge_tower");
        if (btObj == null) return;

        Map<String, Object> bt = null;
        if (btObj instanceof Map<?, ?> mm) bt = safeMap(mm);
        else if (btObj instanceof Boolean b && b) bt = new java.util.LinkedHashMap<>();
        if (bt == null) {
            issues.add(warn("$.macro.bridgeTower", "W_MACRO_BRIDGE_TOWER_TYPE", "bridgeTower 建议是对象（map）或 true（使用默认值）"));
            return;
        }

        // Ensure graph/components exist so we can inject components.
        Map<String, Object> g = graph;
        if (g == null) {
            g = new java.util.LinkedHashMap<>();
            root.put("graph", g);
        }

        Object co = (g != null) ? g.get("components") : compsObj;
        List<Object> comps;
        if (co instanceof List<?> list) comps = (List<Object>) list;
        else {
            comps = new java.util.ArrayList<>();
            g.put("components", comps);
        }

        // Collect existing ids to avoid collisions.
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (Object it : comps) {
            if (it instanceof Map<?, ?> cm) {
                String id = str(((Map<?, ?>) cm).get("id"), "").trim();
                if (!id.isEmpty()) used.add(id);
            }
        }

        String baseId = str(bt.get("id"), "BridgeTower").trim();
        baseId = uniqueId(baseId, used);
        used.add(baseId);

        // Placement (absolute, stored in component.at)
        int ax = 0, ay = 0, az = 0;
        Object at = bt.get("at");
        if (at instanceof Map<?, ?> am) {
            ax = i(am.get("x"), 0);
            ay = i(am.get("y"), 0);
            az = i(am.get("z"), 0);
        } else {
            ax = i(bt.get("x"), 0);
            ay = i(bt.get("y"), 0);
            az = i(bt.get("z"), 0);
        }
        Map<String, Object> atMap = java.util.Map.of("x", ax, "y", ay, "z", az);

        int towerW = clamp(i(bt.get("towerW"), i(bt.get("w"), 4)), 3, 129);
        int towerD = clamp(i(bt.get("towerD"), i(bt.get("d"), 6)), 3, 129);
        int towerH = clamp(i(bt.get("towerH"), i(bt.get("h"), 30)), 6, 255);

        Object towerWall = bt.get("towerWall");
        if (towerWall == null) towerWall = bt.get("towerMaterial");
        Object towerRoof = bt.get("towerRoof");
        if (towerRoof == null) towerRoof = towerWall;
        Object towerFloor = bt.get("towerFloor");
        if (towerFloor == null) towerFloor = "smooth_stone";

        // 1) Tower body
        Map<String, Object> tower = new java.util.LinkedHashMap<>();
        tower.put("id", baseId);
        tower.put("type", "SHELL_BOX");
        tower.put("at", atMap);
        tower.put("w", towerW);
        tower.put("d", towerD);
        tower.put("h", towerH);
        if (towerWall != null) tower.put("wall", towerWall);
        if (towerFloor != null) tower.put("floor", towerFloor);
        if (towerRoof != null) tower.put("roof", towerRoof);

        // Auto semantic ports for cables (aliases near top_center).
        // These are resolved by MetaAssemblyCompiler.buildPorts() and can be referenced as "Tower.saddle_left" etc.
        // Do not overwrite user-provided ports (explicit wins).
        Map<String, Object> ports = null;
        Object portsObj = tower.get("ports");
        if (portsObj instanceof Map<?, ?> pm) ports = safeMap(pm);
        if (ports == null) ports = new java.util.LinkedHashMap<>();
        int saddleYLocal = saddleY - ay;
        int off0 = o0;
        int off1 = o1;
        if ("X".equals(saddleAxis)) {
            putPortIfAbsent(ports, "saddle_left", -Math.abs(off1), saddleYLocal, 0);
            putPortIfAbsent(ports, "saddle_right", Math.abs(off1), saddleYLocal, 0);
            putPortIfAbsent(ports, "saddle_a", off0, saddleYLocal, 0);
            putPortIfAbsent(ports, "saddle_b", off1, saddleYLocal, 0);
        } else {
            putPortIfAbsent(ports, "saddle_left", 0, saddleYLocal, -Math.abs(off1));
            putPortIfAbsent(ports, "saddle_right", 0, saddleYLocal, Math.abs(off1));
            putPortIfAbsent(ports, "saddle_a", 0, saddleYLocal, off0);
            putPortIfAbsent(ports, "saddle_b", 0, saddleYLocal, off1);
        }
        putPortIfAbsent(ports, "saddle_center", 0, saddleYLocal, 0);
        putPortIfAbsent(ports, "cable_top", 0, towerH, 0); // alias of top_center
        putPortIfAbsent(ports, "cable", 0, saddleYLocal, 0);
        if (!ports.isEmpty()) tower.put("ports", ports);
        comps.add(tower);

        // 2) Deep foundation (anchor footprint)
        int margin = clamp(i(bt.get("foundationMargin"), 1), 0, 16);
        int halfW = towerW / 2;
        int halfD = towerD / 2;
        int fx0 = -halfW - margin, fx1 = halfW + margin;
        int fz0 = -halfD - margin, fz1 = halfD + margin;
        int fDepth = clamp(i(bt.get("foundationDepth"), i(bt.get("maxDepth"), 24)), 0, 512);
        Object fMat = bt.get("foundationMaterial");
        if (fMat == null) fMat = "stone_bricks";

        String fId = uniqueId(baseId + "_Foundation", used);
        used.add(fId);
        Map<String, Object> foundation = new java.util.LinkedHashMap<>();
        foundation.put("id", fId);
        foundation.put("type", "ANCHOR_FOOTPRINT");
        foundation.put("at", atMap);
        foundation.put("x0", fx0);
        foundation.put("x1", fx1);
        foundation.put("z0", fz0);
        foundation.put("z1", fz1);
        foundation.put("yBase", ay);
        foundation.put("maxDepth", fDepth);
        foundation.put("material", fMat);
        comps.add(foundation);

        // 3) Saddle rollers (decorative cylinders)
        Object saddleObj = bt.get("saddle");
        Map<String, Object> saddle = (saddleObj instanceof Map<?, ?> sm) ? safeMap(sm) : null;
        // NOTE: CYLINDER validator requires r>=2
        int rollerR = clamp(i(saddle != null ? saddle.get("r") : null, i(bt.get("rollerR"), 2)), 2, 8);
        int rollerH = clamp(i(saddle != null ? saddle.get("h") : null, i(bt.get("rollerH"), 2)), 1, 8);
        int rollerSpacing = clamp(i(saddle != null ? saddle.get("spacing") : null, i(bt.get("rollerSpacing"), 4)), 1, 32);
        String saddleAxis = str(saddle != null ? saddle.get("axis") : null, str(bt.get("saddleAxis"), "Z")).trim().toUpperCase(Locale.ROOT);
        Object rollerMat = (saddle != null && saddle.get("material") != null) ? saddle.get("material") : bt.get("rollerMaterial");
        if (rollerMat == null) rollerMat = (towerWall != null ? towerWall : "iron_block");

        int saddleY = i(bt.get("saddleY"), ay + towerH + 2);
        int o0 = -(rollerSpacing / 2);
        int o1 = (rollerSpacing / 2);

        String s0 = uniqueId(baseId + "_SaddleA", used); used.add(s0);
        String s1 = uniqueId(baseId + "_SaddleB", used); used.add(s1);

        comps.add(makeRoller(s0, ax, saddleY, az, saddleAxis, o0, rollerR, rollerH, rollerMat));
        comps.add(makeRoller(s1, ax, saddleY, az, saddleAxis, o1, rollerR, rollerH, rollerMat));

        issues.add(warn("$.macro.bridgeTower", "W_MACRO_BRIDGE_TOWER",
                "bridgeTower injected: " + baseId + " (SHELL_BOX) + " + fId + " (ANCHOR_FOOTPRINT) + saddle rollers"));
    }

    private static Map<String, Object> makeRoller(String id,
                                                  int ax, int ay, int az,
                                                  String axis,
                                                  int offset,
                                                  int r,
                                                  int h,
                                                  Object material) {
        int x = ax;
        int z = az;
        if ("X".equals(axis)) x = ax + offset;
        else z = az + offset; // default Z
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "CYLINDER");
        c.put("at", java.util.Map.of("x", x, "y", ay, "z", z));
        c.put("r", r);
        c.put("h", h);
        c.put("hollow", false);
        c.put("material", material);
        return c;
    }

    private static void putPortIfAbsent(Map<String, Object> ports, String name, int x, int y, int z) {
        if (ports == null || name == null || name.isBlank()) return;
        if (ports.containsKey(name)) return;
        ports.put(name, java.util.Map.of("x", x, "y", y, "z", z));
    }

    private static String uniqueId(String base, java.util.Set<String> used) {
        String b = (base == null || base.isBlank()) ? "Component" : base.trim();
        if (!used.contains(b)) return b;
        for (int i = 2; i < 1000; i++) {
            String c = b + "_" + i;
            if (!used.contains(c)) return c;
        }
        return b + "_" + System.nanoTime();
    }

    private static void applyShapeType(Map<String, Object> primary, String path, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        String st = str(macro.get("shapeType"), str(macro.get("shape"), null));
        if (st == null || st.isBlank()) return;
        String shape = st.trim().toUpperCase(Locale.ROOT);
        // aliases
        if (shape.equals("RECT") || shape.equals("RECTANGLE") || shape.equals("BOX")) shape = "RECTANGLE";
        if (shape.equals("CIRCLE") || shape.equals("CYL") || shape.equals("CYLINDER")) shape = "CIRCLE";
        if (shape.equals("HEX")) shape = "HEXAGON";

        // Only touch when type is one of the base body primitives.
        String type = str(primary.get("type"), str(primary.get("op"), "")).trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) return;

        if (shape.equals("CIRCLE")) {
            // Convert a SHELL_BOX-like body into a CYLINDER (best-effort).
            if (type.contains("SHELL_BOX") || type.contains("BOX_SHELL")) {
                // r from w/d (if present)
                Integer w = intOrNull(primary.get("w"));
                Integer d = intOrNull(primary.get("d"));
                int r = 6;
                if (w != null || d != null) {
                    int ww = (w != null) ? w : (d != null ? d : 15);
                    int dd = (d != null) ? d : (w != null ? w : 15);
                    r = Math.max(2, Math.round((ww + dd) / 4.0f));
                }
                // preserve height
                Integer h = intOrNull(primary.get("h"));
                primary.put("type", "CYLINDER");
                primary.remove("w");
                primary.remove("d");
                primary.put("r", r);
                if (h != null) primary.put("h", h);
                issues.add(warn(path, "W_MACRO_SHAPE", "shapeType=CIRCLE: converted main component from SHELL_BOX to CYLINDER (r=" + r + ")"));
            }
        } else if (shape.equals("RECTANGLE")) {
            // If a cylinder was chosen but macro wants rectangle, convert to SHELL_BOX using r.
            if (type.contains("CYLINDER")) {
                Integer r = intOrNull(primary.get("r"));
                if (r == null) r = intOrNull(primary.get("radius"));
                int w = (r != null) ? (r * 2 + 1) : 15;
                Integer h = intOrNull(primary.get("h"));
                primary.put("type", "SHELL_BOX");
                primary.remove("r");
                primary.remove("radius");
                primary.put("w", w);
                primary.put("d", w);
                if (h != null) primary.put("h", h);
                issues.add(warn(path, "W_MACRO_SHAPE", "shapeType=RECTANGLE: converted main component from CYLINDER to SHELL_BOX (w=d=" + w + ")"));
            }
        } else if (shape.equals("HEXAGON") || shape.equals("HEXAGONAL")) {
            if (type.contains("SHELL_BOX") || type.contains("BOX_SHELL")) {
                Integer w = intOrNull(primary.get("w"));
                Integer d = intOrNull(primary.get("d"));
                Integer h = intOrNull(primary.get("h"));
                if (w == null || d == null || h == null) return;

                int r = Math.max(3, Math.round((w + d) / 6.0f));
                List<Map<String, Object>> points = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    double ang = (Math.PI * 2.0) * (i / 6.0);
                    int px = (int) Math.round(Math.cos(ang) * r);
                    int pz = (int) Math.round(Math.sin(ang) * r);
                    points.add(java.util.Map.of("x", px, "z", pz));
                }

                primary.put("type", "EXTRUDE_POLYGON");
                primary.put("shape", "POINTS");
                primary.put("points", points);
                primary.put("h", h);
                primary.remove("w");
                primary.remove("d");
                issues.add(warn(path, "W_MACRO_SHAPE", "shapeType=HEXAGON: converted main component from SHELL_BOX to EXTRUDE_POLYGON (r≈" + r + ")"));
            } else {
                issues.add(warn(path, "W_MACRO_SHAPE_UNSUPPORTED", "shapeType=HEXAGON currently supports only SHELL_BOX-like primary components"));
            }
        } else {
            // For now, just warn (future: HEXAGON -> EXTRUDE_POLYGON)
            issues.add(warn(path, "W_MACRO_SHAPE_UNSUPPORTED", "shapeType unsupported: " + st));
        }
    }

    private static void applyHeightScale(Map<String, Object> primary, String path, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        Object hs = macro.get("heightScale");
        if (hs == null) hs = macro.get("height_scale");
        if (hs == null) return;

        double scale = Double.NaN;
        if (hs instanceof Number n) scale = n.doubleValue();
        else {
            String s = String.valueOf(hs).trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) return;
            if (s.equals("LOW") || s.equals("SHORT")) scale = 0.7;
            else if (s.equals("MEDIUM") || s.equals("MID")) scale = 1.0;
            else if (s.equals("HIGH") || s.equals("TALL")) scale = 1.6;
            else {
                try { scale = Double.parseDouble(s); } catch (Exception ignored) {}
            }
        }
        if (Double.isNaN(scale) || scale <= 0.0) return;
        if (scale < 0.2) scale = 0.2;
        if (scale > 4.0) scale = 4.0;

        // Apply to h/height when present; do not invent if absent.
        Integer h = intOrNull(primary.get("h"));
        if (h == null) h = intOrNull(primary.get("height"));
        if (h == null) return;
        int nh = Math.max(2, (int) Math.round(h * scale));
        primary.put("h", nh);
        issues.add(warn(path, "W_MACRO_HEIGHT", "heightScale applied to primary.h: " + h + " -> " + nh));
    }

    private static void applyRoofMacro(Map<String, Object> root,
                                       Map<String, Object> graph,
                                       Object compsObj,
                                       Map<String, Object> primary,
                                       Map<String, Object> macro,
                                       List<AssemblyValidationIssue> issues) {
        String roofType = str(macro.get("roofType"), str(macro.get("roof_type"), null));
        if (roofType == null || roofType.isBlank()) return;
        String rt = roofType.trim().toUpperCase(Locale.ROOT);
        if (rt.equals("FLAT") || rt.equals("GABLE")) {
            // Ensure a ROOF_COVER component exists; if none, add one (best-effort).
            if (!(compsObj instanceof List<?> list)) return;
            @SuppressWarnings("unchecked")
            List<Object> comps = (List<Object>) list;
            boolean hasRoof = false;
            for (Object it : comps) {
                if (!(it instanceof Map<?, ?> cm)) continue;
                String t = str(((Map<?, ?>) cm).get("type"), str(((Map<?, ?>) cm).get("op"), "")).trim().toUpperCase(Locale.ROOT);
                if (t.contains("ROOF")) { hasRoof = true; break; }
            }

            Integer rise = roofCurvatureToRise(macro.get("roofCurvature"), macro.get("roof_curvature"), primary);
            if (hasRoof) {
                // If roof exists but has no explicit rise, inject a default rise (best-effort)
                if (rise != null) {
                    for (Object it : comps) {
                        if (!(it instanceof Map<?, ?> cm)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> c = (Map<String, Object>) cm;
                        String t = str(c.get("type"), str(c.get("op"), "")).trim().toUpperCase(Locale.ROOT);
                        if (!t.contains("ROOF")) continue;
                        if (c.get("rise") == null) {
                            c.put("rise", rise);
                            issues.add(warn("$.macro.roofCurvature", "W_MACRO_ROOF_CURVATURE", "roofCurvature applied to existing ROOF_COVER.rise=" + rise));
                        }
                    }
                }
                return;
            }

            Integer h = intOrNull(primary.get("h"));
            String pt = str(primary.get("type"), "").trim().toUpperCase(Locale.ROOT);
            if (h == null) return;

            Integer w = intOrNull(primary.get("w"));
            Integer d = intOrNull(primary.get("d"));
            if (w == null || d == null) {
                // Support EXTRUDE_POLYGON by deriving bbox from points
                if (pt.contains("EXTRUDE")) {
                    int[] bb = polygonBoundsXZ(primary.get("points"));
                    if (bb != null) {
                        w = Math.max(3, bb[1] - bb[0] + 1);
                        d = Math.max(3, bb[3] - bb[2] + 1);
                    }
                }
            }
            if (w == null || d == null) return;

            int overhang = overhangToInt(str(macro.get("overhang"), null));
            Map<String, Object> roof = new java.util.LinkedHashMap<>();
            roof.put("id", "Roof");
            roof.put("type", "ROOF_COVER");
            roof.put("w", w);
            roof.put("d", d);
            roof.put("y", h + 1);
            roof.put("roofType", rt);
            if (overhang > 0) roof.put("overhang", overhang);
            if (rise != null && rt.equals("GABLE")) roof.put("rise", rise);
            comps.add(roof);
            issues.add(warn("$.macro.roofType", "W_MACRO_ROOF_ADD", "auto-added ROOF_COVER (roofType=" + rt + ")"));
        }
    }

    private static Integer roofCurvatureToRise(Object a, Object b, Map<String, Object> primary) {
        Object v = (a != null) ? a : b;
        if (v == null) return null;
        double t = Double.NaN;
        if (v instanceof Number n) t = n.doubleValue();
        else {
            String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) return null;
            if (s.equals("LOW") || s.equals("SMALL")) t = 0.25;
            else if (s.equals("MEDIUM") || s.equals("MID")) t = 0.5;
            else if (s.equals("HIGH") || s.equals("TALL")) t = 0.85;
            else if (s.contains("FLAT") || s.contains("平")) t = 0.0;
            else if (s.contains("STEEP") || s.contains("陡")) t = 1.0;
            else {
                try { t = Double.parseDouble(s); } catch (Exception ignored) {}
            }
        }
        if (Double.isNaN(t)) return null;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        int w = intOrNull(primary.get("w")) != null ? intOrNull(primary.get("w")) : 15;
        int d = intOrNull(primary.get("d")) != null ? intOrNull(primary.get("d")) : 15;
        int base = Math.max(2, Math.min(8, Math.max(w, d) / 6));
        return Math.max(1, (int) Math.round(2 + t * (base + 6)));
    }

    private static int[] polygonBoundsXZ(Object ptsObj) {
        if (!(ptsObj instanceof List<?> pts) || pts.isEmpty()) return null;
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        boolean any = false;
        for (Object it : pts) {
            if (!(it instanceof Map<?, ?> pm)) continue;
            Integer x = intOrNull(pm.get("x"));
            Integer z = intOrNull(pm.get("z"));
            if (x == null || z == null) continue;
            xMin = Math.min(xMin, x);
            xMax = Math.max(xMax, x);
            zMin = Math.min(zMin, z);
            zMax = Math.max(zMax, z);
            any = true;
        }
        if (!any) return null;
        return new int[]{xMin, xMax, zMin, zMax};
    }

    private static int overhangToInt(String s) {
        if (s == null) return 0;
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return 0;
        return switch (t) {
            case "NONE", "NO" -> 0;
            case "SMALL" -> 1;
            case "MEDIUM" -> 2;
            case "LARGE", "WIDE" -> 3;
            default -> {
                try { yield Math.max(0, Integer.parseInt(t)); } catch (Exception e) { yield 0; }
            }
        };
    }

    private static void applyOpenness(Map<String, Object> primary, String path, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        Object ov = macro.get("openness");
        if (ov == null) return;
        double o = Double.NaN;
        if (ov instanceof Number n) o = n.doubleValue();
        else {
            String s = String.valueOf(ov).trim();
            if (s.isEmpty()) return;
            try { o = Double.parseDouble(s); } catch (Exception ignored) {}
        }
        if (Double.isNaN(o)) return;
        if (o < 0.0) o = 0.0;
        if (o > 1.0) o = 1.0;

        // Best-effort: adjust openings density if facade.openings exists
        Object facadeObj = primary.get("facade");
        if (!(facadeObj instanceof Map<?, ?> fm)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> facade = (Map<String, Object>) fm;
        Object openingsObj = facade.get("openings");
        if (!(openingsObj instanceof List<?> list) || list.isEmpty()) return;

        for (int i = 0; i < list.size(); i++) {
            Object it = list.get(i);
            if (!(it instanceof Map<?, ?> mm)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> opening = (Map<String, Object>) mm;
            String kind = str(opening.get("kind"), str(opening.get("type"), "")).trim().toUpperCase(Locale.ROOT);
            if (kind.isBlank()) continue;
            if (kind.contains("WINDOW_GRID") || kind.contains("ARCH")) {
                // Scale rows/cols into a reasonable range
                int baseCols = intOrNull(opening.get("cols")) != null ? intOrNull(opening.get("cols")) : 3;
                int baseRows = intOrNull(opening.get("rows")) != null ? intOrNull(opening.get("rows")) : 2;
                int cols = clampInt((int) Math.round(2 + o * 10), 1, 24);
                int rows = clampInt((int) Math.round(1 + o * 6), 1, 12);
                opening.put("cols", cols);
                opening.put("rows", rows);
                issues.add(warn(path + ".facade.openings[" + i + "]", "W_MACRO_OPENNESS", "openness=" + o + ": rows/cols " + baseRows + "/" + baseCols + " -> " + rows + "/" + cols));
            }
        }
    }

    private static void applySymmetryToConnections(List<?> conns, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        String sym = str(macro.get("symmetry"), null);
        if (sym == null || sym.isBlank()) return;
        String s = sym.trim().toUpperCase(Locale.ROOT);
        // map symmetry to routing style defaults
        String routingStyle = null;
        if (s.contains("ASYM") || s.contains("RANDOM")) routingStyle = "ORGANIC";
        else if (s.contains("AXIS") || s.contains("RADIAL") || s.contains("SYMM")) routingStyle = "PLANNED";
        if (routingStyle == null) return;

        for (int i = 0; i < conns.size(); i++) {
            Object it = conns.get(i);
            if (!(it instanceof Map<?, ?> cm)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) cm;
            if (c.get("routingStyle") != null) continue; // explicit wins
            c.put("routingStyle", routingStyle);
            issues.add(warn("$.macro.symmetry", "W_MACRO_SYMMETRY", "injected connections[" + i + "].routingStyle=" + routingStyle));
        }
    }

    // ---------------- helpers ----------------

    private static int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static int i(Object v, int def) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Map<?, ?> mm) {
        try {
            return (Map<String, Object>) mm;
        } catch (Exception ignored) {
            return null;
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

    private static AssemblyValidationIssue warn(String path, String code, String msg) {
        return new AssemblyValidationIssue(path, AssemblyValidationIssue.Severity.WARNING, code, msg);
    }
}


