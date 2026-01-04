package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P0 Macro parameter table applier.
 * <p>
 * Reads extra.assembly.macro and maps high-level "gene-like" sliders onto existing assembly knobs.
 * <p>
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

        // Apply verticalProfile FIRST (it replaces the primary component with segments)
        // Must run before other macros that modify the primary component
        if (primary != null) {
            primary = applyVerticalProfile(m, graph, compsObj, primary, macro, issues);
        }

        // Apply shapeType & heightScale to primary component (best-effort).
        if (primary != null) {
            applyShapeType(primary, macro, issues);
            applyHeightScale(primary, macro, issues);
            applyRoofMacro(m, graph, compsObj, primary, macro, issues);
            applyOpenness(primary, macro, issues);
        }

        // Apply symmetry macro to routing defaults (connections)
        if (connsObj instanceof List<?> conns) {
            applySymmetryToConnections(conns, macro, issues);
        }

        // Style/culture macro: map higher-level cultural sliders onto atomic facade/structure primitives.
        applyStyleMacro(m, graph, compsObj, connsObj, primary, macro, issues);

        // Bridge tower macro: inject ANCHOR_FOOTPRINT + tower body + saddle rollers
        applyBridgeTower(m, graph, compsObj, macro, issues);

        // Subtract holes macro: inject CLEAR_BOX components for boolean subtraction (e.g., courtyards)
        applySubtractHoles(m, graph, compsObj, primary, macro, issues);

        return new AssemblyMacroApplyResult(m, issues);
    }

    @SuppressWarnings("unchecked")
    private static void applyStyleMacro(Map<String, Object> root,
                                        Map<String, Object> graph,
                                        Object compsObj,
                                        Object connsObj,
                                        Map<String, Object> primary,
                                        Map<String, Object> macro,
                                        List<AssemblyValidationIssue> issues) {
        Object styleObj = macro.get("style");
        if (styleObj == null) styleObj = macro.get("culture");
        if (styleObj == null) return;

        Map<String, Object> style = null;
        if (styleObj instanceof Map<?, ?> mm) style = safeMap(mm);
        if (style == null) {
            issues.add(warn("$.macro.style", "W_MACRO_STYLE_TYPE", "macro.style/culture 建议是对象（map）"));
            return;
        }

        String styleId = str(style.get("styleId"), str(style.get("style_id"), str(style.get("id"), null)));
        if (styleId != null) styleId = styleId.trim();
        String intent = str(style.get("intent"), str(style.get("mood"), null));
        if (intent != null) intent = intent.trim();

        double density = clamp01(d(style.get("density"), 0.6));
        double symmetry = clamp01(d(style.get("symmetry"), 0.6));
        double verticality = clamp01(d(style.get("verticality"), 0.6));
        double transparency = clamp01(d(style.get("transparency"), 0.4));
        double structureExposure = clamp01(d(style.get("structureExposure"), d(style.get("structure_exposure"), 0.5)));

        // Palette mapping (best-effort, only if paletteId is absent)
        if (root.get("paletteId") == null) {
            String sid = (styleId != null) ? styleId.toUpperCase(Locale.ROOT) : "";
            String it = (intent != null) ? intent.toUpperCase(Locale.ROOT) : "";
            String pal = null;
            if (sid.contains("GOTHIC") || it.contains("神圣") || it.contains("SACRED")) pal = "PALETTE_GOTHIC_CATHEDRAL_A";
            else if (sid.contains("INDUSTRIAL") || it.contains("工业") || it.contains("INDUSTRIAL")) pal = "PALETTE_INDUSTRIAL_STEEL_A";
            if (pal != null) {
                root.put("paletteId", pal);
                issues.add(warn("$.macro.style.paletteId", "W_MACRO_STYLE_PALETTE", "style macro set paletteId=" + pal));
            }
        }

        if (primary == null) {
            issues.add(warn("$.macro.style", "W_MACRO_STYLE_NO_PRIMARY", "style macro: 没有可作用的主组件（components 为空或 primaryComponent 未找到）"));
            return;
        }
        String pType = str(primary.get("type"), str(primary.get("op"), "")).trim().toUpperCase(Locale.ROOT);
        if (!(pType.contains("SHELL_BOX") || pType.contains("BOX_SHELL"))) {
            issues.add(warn("$.macro.style", "W_MACRO_STYLE_PRIMARY_UNSUPPORTED", "style macro 当前仅对 SHELL_BOX 主组件做注入（当前=" + pType + "）"));
            return;
        }

        Integer w = intOrNull(primary.get("w"));
        Integer dep = intOrNull(primary.get("d"));
        Integer h = intOrNull(primary.get("h"));
        if (w == null || dep == null || h == null) {
            issues.add(warn("$.macro.style", "W_MACRO_STYLE_PRIMARY_DIM", "style macro 需要主组件含 w/d/h"));
            return;
        }

        Map<String, Object> facade = null;
        Object fObj = primary.get("facade");
        if (fObj instanceof Map<?, ?> fm) facade = safeMap(fm);
        if (facade == null) {
            facade = new java.util.LinkedHashMap<>();
            primary.put("facade", facade);
        }

        // Which style bucket?
        String sid = (styleId != null) ? styleId.toUpperCase(Locale.ROOT) : "";
        String it = (intent != null) ? intent.toUpperCase(Locale.ROOT) : "";
        boolean gothic = sid.contains("GOTHIC") || it.contains("神圣") || it.contains("SACRED");
        boolean industrial = sid.contains("INDUSTRIAL") || it.contains("工业") || it.contains("INDUSTRIAL");

        // 1) Gothic: pointed arches + rose window + vertical rhythm + (optional) buttresses
        if (gothic) {
            if (facade.get("openings") == null && facade.get("opening") == null) {
                int cols = clampInt((int) Math.round(3 + density * 6), 2, 12);
                int rows = clampInt((int) Math.round(1 + density * 2), 1, 4);
                int winH = clampInt((int) Math.round(6 + verticality * 10), 5, Math.max(6, h - 4));
                int sillY = clampInt((int) Math.round(3 + (1.0 - verticality) * 3), 1, Math.max(1, h - winH - 2));
                List<Map<String, Object>> openings = new ArrayList<>();
                openings.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "face", "NORTH,SOUTH",
                        "kind", "ARCH_WINDOW",
                        "archType", "POINTED",
                        "cols", cols,
                        "rows", rows,
                        "winW", 2,
                        "winH", winH,
                        "sillY", sillY
                )));
                openings.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "face", "WEST,EAST",
                        "kind", "ROSE_WINDOW",
                        "r", clampInt(Math.max(4, Math.min(w, dep) / 3), 4, 10),
                        "centerY", clampInt(h - 6, 6, h - 2),
                        "petals", clampInt((int) Math.round(6 + density * 8), 5, 16)
                )));
                facade.put("openings", openings);
                issues.add(warn("$.macro.style", "W_MACRO_STYLE_GOTHIC_OPENINGS", "style=gothic: injected facade.openings (ARCH_WINDOW + ROSE_WINDOW)"));
            }

            if (facade.get("surfaceBands") == null && facade.get("SURFACE_BANDS") == null && facade.get("bands") == null) {
                int step = clampInt((int) Math.round(6 - density * 3), 2, 8);
                Map<String, Object> sb = new java.util.LinkedHashMap<>();
                sb.put("verticalBands", java.util.List.of(java.util.Map.of(
                        "step", step,
                        "thickness", 1,
                        "outset", clampInt((int) Math.round(structureExposure * 2), 0, 2),
                        "material", "minecraft:stone_brick_wall"
                )));
                facade.put("surfaceBands", sb);
                issues.add(warn("$.macro.style", "W_MACRO_STYLE_GOTHIC_BANDS", "style=gothic: injected facade.surfaceBands vertical rhythm"));
            }

            // Optional buttresses if structure exposure is high.
            if (structureExposure > 0.55) {
                // Ensure graph/components list
                Map<String, Object> g = graph;
                if (g == null) {
                    g = new java.util.LinkedHashMap<>();
                    root.put("graph", g);
                }
                Object co = g.get("components");
                List<Object> comps;
                if (co instanceof List<?> list) comps = (List<Object>) list;
                else { comps = new ArrayList<>(); g.put("components", comps); }

                java.util.HashSet<String> used = new java.util.HashSet<>();
                for (Object it0 : comps) if (it0 instanceof Map<?, ?> cm) {
                    String id0 = str(cm.get("id"), "").trim();
                    if (!id0.isEmpty()) used.add(id0);
                }

                int hx = w / 2;
                int hz = dep / 2;
                int fromY = clampInt(h - 4, 6, h);
                int toY = clampInt(h - 8, 4, h);
                int pierDown = clampInt((int) Math.round(6 + verticality * 10), 4, 24);
                // Two buttresses on left/right at mid-z
                String idL = uniqueId("ButtressL", used); used.add(idL);
                String idR = uniqueId("ButtressR", used); used.add(idR);
                comps.add(java.util.Map.of(
                        "id", idL,
                        "type", "BUTTRESS",
                        "from", java.util.Map.of("x", -hx, "y", fromY, "z", 0),
                        "to", java.util.Map.of("x", -hx - 4, "y", toY, "z", 0),
                        "rise", clampInt((int) Math.round(3 + verticality * 5), 2, 12),
                        "pierDown", pierDown
                ));
                comps.add(java.util.Map.of(
                        "id", idR,
                        "type", "BUTTRESS",
                        "from", java.util.Map.of("x", hx, "y", fromY, "z", 0),
                        "to", java.util.Map.of("x", hx + 4, "y", toY, "z", 0),
                        "rise", clampInt((int) Math.round(3 + verticality * 5), 2, 12),
                        "pierDown", pierDown
                ));
                issues.add(warn("$.macro.style", "W_MACRO_STYLE_GOTHIC_BUTTRESS", "style=gothic: injected BUTTRESS components"));
            }
        }

        // 2) Industrial: exoskeleton frame grid around primary
        if (industrial) {
            Map<String, Object> g = graph;
            if (g == null) {
                g = new java.util.LinkedHashMap<>();
                root.put("graph", g);
            }
            Object co = g.get("components");
            List<Object> comps;
            if (co instanceof List<?> list) comps = (List<Object>) list;
            else { comps = new ArrayList<>(); g.put("components", comps); }

            // Avoid injecting duplicates if already has FRAME_GRID_3D
            boolean hasFrame = false;
            for (Object it0 : comps) {
                if (it0 instanceof Map<?, ?> cm) {
                    String t = str(cm.get("type"), str(cm.get("op"), "")).trim().toUpperCase(Locale.ROOT);
                    if (t.contains("FRAME_GRID_3D")) { hasFrame = true; break; }
                }
            }
            if (!hasFrame && structureExposure > 0.25) {
                int exW = w + 4;
                int exD = dep + 4;
                int exH = h + 4;
                int step = clampInt((int) Math.round(6 - density * 4), 2, 8);
                String diag = (structureExposure > 0.75) ? "SPACE" : "FACE";
                Map<String, Object> frame = new java.util.LinkedHashMap<>();
                frame.put("id", "Exoskeleton");
                frame.put("type", "FRAME_GRID_3D");
                frame.put("w", exW);
                frame.put("d", exD);
                frame.put("h", exH);
                frame.put("stepX", step);
                frame.put("stepY", step);
                frame.put("stepZ", step);
                frame.put("mode", "SURFACE");
                frame.put("diagonal", diag);
                comps.add(frame);
                issues.add(warn("$.macro.style", "W_MACRO_STYLE_INDUSTRIAL_FRAME", "style=industrial: injected FRAME_GRID_3D exoskeleton (diag=" + diag + ")"));
            }
        }

        // Symmetry slider (numeric) -> routingStyle defaults if not explicitly set.
        if (connsObj instanceof List<?> conns) {
            for (Object it0 : conns) {
                if (!(it0 instanceof Map<?, ?> cm)) continue;
                Map<String, Object> c = safeMap(cm);
                if (c == null) continue;
                if (c.get("routingStyle") != null) continue;
                String rs = (symmetry >= 0.5) ? "PLANNED" : "ORGANIC";
                c.put("routingStyle", rs);
            }
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    private static double d(Object v, double def) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return def;
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

        Object co = g.get("components");
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
                String id = str(cm.get("id"), "").trim();
                if (!id.isEmpty()) used.add(id);
            }
        }

        String baseId = str(bt.get("id"), "BridgeTower").trim();
        baseId = uniqueId(baseId, used);
        used.add(baseId);

        // Placement (absolute, stored in component.at)
        int ax, ay, az;
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

        // Saddle rollers config (computed early so we can also expose semantic ports on the tower)
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

        // 1) Tower body
        Map<String, Object> tower = new java.util.LinkedHashMap<>();
        tower.put("id", baseId);
        tower.put("type", "SHELL_BOX");
        tower.put("at", atMap);
        tower.put("w", towerW);
        tower.put("d", towerD);
        tower.put("h", towerH);
        if (towerWall != null) tower.put("wall", towerWall);
        tower.put("floor", towerFloor);
        if (towerRoof != null) tower.put("roof", towerRoof);

        // Auto semantic ports for cables (aliases near top_center).
        // These are resolved by MetaAssemblyCompiler.buildPorts() and can be referenced as "Tower.saddle_left" etc.
        // Do not overwrite user-provided ports (explicit wins).
        Map<String, Object> ports = null;
        Object portsObj = tower.get("ports");
        if (portsObj instanceof Map<?, ?> pm) ports = safeMap(pm);
        if (ports == null) ports = new java.util.LinkedHashMap<>();
        int saddleYLocal = saddleY - ay;
        if ("X".equals(saddleAxis)) {
            putPortIfAbsent(ports, "saddle_left", -Math.abs(o1), saddleYLocal, 0);
            putPortIfAbsent(ports, "saddle_right", Math.abs(o1), saddleYLocal, 0);
            putPortIfAbsent(ports, "saddle_a", o0, saddleYLocal, 0);
            putPortIfAbsent(ports, "saddle_b", o1, saddleYLocal, 0);
        } else {
            putPortIfAbsent(ports, "saddle_left", 0, saddleYLocal, -Math.abs(o1));
            putPortIfAbsent(ports, "saddle_right", 0, saddleYLocal, Math.abs(o1));
            putPortIfAbsent(ports, "saddle_a", 0, saddleYLocal, o0);
            putPortIfAbsent(ports, "saddle_b", 0, saddleYLocal, o1);
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
        String s0 = uniqueId(baseId + "_SaddleA", used); used.add(s0);
        String s1 = uniqueId(baseId + "_SaddleB", used); used.add(s1);

        comps.add(makeRoller(s0, ax, saddleY, az, saddleAxis, o0, rollerR, rollerH, rollerMat));
        comps.add(makeRoller(s1, ax, saddleY, az, saddleAxis, o1, rollerR, rollerH, rollerMat));

        // 4) Carve notch + cable holes on tower top (macro-friendly: inject CLEAR_BOX components)
        Object notchObj = bt.get("notch");
        Map<String, Object> notch = (notchObj instanceof Map<?, ?> nm) ? safeMap(nm) : null;
        boolean notchOn = (notch != null) ? bool(notch.get("enabled"), true) : bool(bt.get("notchEnabled"), false);
        if (notchOn) {
            int notchDepth = clamp(i(notch != null ? notch.get("depth") : null, i(bt.get("notchDepth"), 2)), 1, 12);
            int notchWidth = clamp(i(notch != null ? notch.get("width") : null, i(bt.get("notchWidth"), 2)), 1, 64);
            int notchLen = clamp(i(notch != null ? notch.get("length") : null, i(bt.get("notchLength"), rollerSpacing + 4)), 2, 128);
            int notchTopY = i(notch != null ? notch.get("topY") : null, i(bt.get("notchTopY"), towerH + 1));
            // local bounds (tower-centered, like SHELL_BOX)
            int nx0, nx1, nz0, nz1;
            if ("X".equals(saddleAxis)) {
                nx0 = -(notchLen / 2);
                nx1 = (notchLen / 2);
                nz0 = -(notchWidth / 2);
                nz1 = (notchWidth / 2);
            } else {
                nz0 = -(notchLen / 2);
                nz1 = (notchLen / 2);
                nx0 = -(notchWidth / 2);
                nx1 = (notchWidth / 2);
            }
            // clamp inside tower footprint
            nx0 = Math.max(nx0, -halfW);
            nx1 = Math.min(nx1, halfW);
            nz0 = Math.max(nz0, -halfD);
            nz1 = Math.min(nz1, halfD);
            int ny1 = notchTopY;
            int ny0 = notchTopY - notchDepth + 1;
            ny0 = Math.max(0, ny0);
            ny1 = Math.min(towerH + 1, ny1);
            if (nx0 <= nx1 && nz0 <= nz1 && ny0 <= ny1) {
                String nid = uniqueId(baseId + "_SaddleNotch", used); used.add(nid);
                Map<String, Object> carve = new java.util.LinkedHashMap<>();
                carve.put("id", nid);
                carve.put("type", "CLEAR_BOX");
                carve.put("at", atMap);
                carve.put("x0", nx0); carve.put("y0", ny0); carve.put("z0", nz0);
                carve.put("x1", nx1); carve.put("y1", ny1); carve.put("z1", nz1);
                comps.add(carve);
            }
        }

        Object holesObj = bt.get("holes");
        if (holesObj == null) holesObj = bt.get("cableHoles");
        if (holesObj instanceof List<?> holes) {
            for (int hi = 0; hi < holes.size(); hi++) {
                Object hv = holes.get(hi);
                if (!(hv instanceof Map<?, ?> hm)) continue;
                Map<String, Object> hole = safeMap(hm);
                if (hole == null) continue;
                String face = str(hole.get("face"), "").trim().toUpperCase(Locale.ROOT);
                if (face.isBlank()) continue;
                int hy = i(hole.get("y"), towerH);
                int r = clamp(i(hole.get("r"), i(hole.get("radius"), 1)), 1, 8);
                int len = clamp(i(hole.get("len"), i(hole.get("length"), 6)), 1, 128);
                int hx = i(hole.get("x"), 0);
                int hz = i(hole.get("z"), 0);

                // convert hole spec to a clear box
                int cx0, cx1, cz0, cz1;
                int cy0 = Math.max(0, hy - r), cy1 = Math.min(towerH + 1, hy + r);

                if (face.equals("EAST") || face.equals("WEST")) {
                    int sx = face.equals("WEST") ? -halfW : halfW;
                    int ex = sx + (face.equals("WEST") ? len : -len);
                    cx0 = Math.min(sx, ex); cx1 = Math.max(sx, ex);
                    cz0 = Math.max(-halfD, hz - r); cz1 = Math.min(halfD, hz + r);
                } else if (face.equals("NORTH") || face.equals("SOUTH")) {
                    int sz = face.equals("NORTH") ? -halfD : halfD;
                    int ez = sz + (face.equals("NORTH") ? len : -len);
                    cz0 = Math.min(sz, ez); cz1 = Math.max(sz, ez);
                    cx0 = Math.max(-halfW, hx - r); cx1 = Math.min(halfW, hx + r);
                } else {
                    continue;
                }
                // clamp inside
                cx0 = Math.max(cx0, -halfW); cx1 = Math.min(cx1, halfW);
                cz0 = Math.max(cz0, -halfD); cz1 = Math.min(cz1, halfD);
                if (cx0 <= cx1 && cz0 <= cz1 && cy0 <= cy1) {
                    String hid = uniqueId(baseId + "_CableHole_" + (hi + 1), used); used.add(hid);
                    Map<String, Object> carve = new java.util.LinkedHashMap<>();
                    carve.put("id", hid);
                    carve.put("type", "CLEAR_BOX");
                    carve.put("at", atMap);
                    carve.put("x0", cx0); carve.put("y0", cy0); carve.put("z0", cz0);
                    carve.put("x1", cx1); carve.put("y1", cy1); carve.put("z1", cz1);
                    comps.add(carve);
                }
            }
        }

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

    private static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static void applySubtractHoles(Map<String, Object> root,
                                           Map<String, Object> graph,
                                           Object compsObj,
                                           Map<String, Object> primary,
                                           Map<String, Object> macro,
                                           List<AssemblyValidationIssue> issues) {
        Object holesObj = macro.get("subtractHoles");
        if (holesObj == null) holesObj = macro.get("subtract_holes");
        if (holesObj == null) return;
        if (!(holesObj instanceof List<?> holesList)) return;
        if (holesList.isEmpty()) return;

        if (primary == null) return;
        if (!(compsObj instanceof List<?> list)) return;
        @SuppressWarnings("unchecked")
        List<Object> comps = (List<Object>) list;

        Integer pw = intOrNull(primary.get("w"));
        Integer pd = intOrNull(primary.get("d"));
        Integer ph = intOrNull(primary.get("h"));
        if (pw == null || pd == null || ph == null) return;

        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (Object it0 : comps) if (it0 instanceof Map<?, ?> cm) {
            String id0 = str(cm.get("id"), "").trim();
            if (!id0.isEmpty()) used.add(id0);
        }

        int hx = pw / 2;
        int hz = pd / 2;

        for (int i = 0; i < holesList.size(); i++) {
            Object holeObj = holesList.get(i);
            if (!(holeObj instanceof Map<?, ?> holeMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> hole = (Map<String, Object>) holeMap;

            String holeType = str(hole.get("type"), "RECTANGLE").trim().toUpperCase(Locale.ROOT);
            if (!holeType.equals("RECTANGLE") && !holeType.equals("RECT")) continue;

            Integer hw = intOrNull(hole.get("w"));
            Integer hd = intOrNull(hole.get("d"));
            if (hw == null || hd == null) continue;
            if (hw <= 0 || hd <= 0) continue;

            // Position: default to center (0,0), can override with at.x/z
            Object atObj = hole.get("at");
            int hcx = 0, hcz = 0;
            if (atObj instanceof Map<?, ?> atMap) {
                hcx = intOrNull(atMap.get("x")) != null ? intOrNull(atMap.get("x")) : 0;
                hcz = intOrNull(atMap.get("z")) != null ? intOrNull(atMap.get("z")) : 0;
            }

            // Calculate CLEAR_BOX bounds (relative to primary component origin)
            int x0 = hcx - hw / 2;
            int x1 = hcx + hw / 2 - 1;
            int z0 = hcz - hd / 2;
            int z1 = hcz + hd / 2 - 1;
            int y0 = 0; // Start from bottom
            int y1 = ph - 1; // Full height

            String holeId = uniqueId("Hole" + (i + 1), used);
            used.add(holeId);

            Map<String, Object> clearBox = new java.util.LinkedHashMap<>();
            clearBox.put("id", holeId);
            clearBox.put("type", "CLEAR_BOX");
            clearBox.put("x0", x0);
            clearBox.put("y0", y0);
            clearBox.put("z0", z0);
            clearBox.put("x1", x1);
            clearBox.put("y1", y1);
            clearBox.put("z1", z1);

            comps.add(clearBox);
        }

        if (!holesList.isEmpty()) {
            issues.add(warn("$.macro.subtractHoles", "W_MACRO_SUBTRACT_HOLES", "subtractHoles injected " + holesList.size() + " CLEAR_BOX components"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applyVerticalProfile(Map<String, Object> root,
                                                            Map<String, Object> graph,
                                                            Object compsObj,
                                                            Map<String, Object> primary,
                                                            Map<String, Object> macro,
                                                            List<AssemblyValidationIssue> issues) {
        Object vpObj = macro.get("verticalProfile");
        if (vpObj == null) vpObj = macro.get("vertical_profile");
        if (vpObj == null) return primary; // No vertical profile, return original
        if (!(vpObj instanceof List<?> vpList)) return primary;
        if (vpList.isEmpty()) return primary;

        // Only support SHELL_BOX for now
        String pType = str(primary.get("type"), str(primary.get("op"), "")).trim().toUpperCase(Locale.ROOT);
        if (!(pType.contains("SHELL_BOX") || pType.contains("BOX_SHELL"))) {
            issues.add(warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_UNSUPPORTED", "verticalProfile 当前仅支持 SHELL_BOX（当前=" + pType + "）"));
            return primary;
        }

        Integer pw = intOrNull(primary.get("w"));
        Integer pd = intOrNull(primary.get("d"));
        Integer ph = intOrNull(primary.get("h"));
        if (pw == null || pd == null || ph == null) {
            issues.add(warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_DIM", "verticalProfile 需要主组件含 w/d/h"));
            return primary;
        }

        // Ensure graph/components exist
        Map<String, Object> g = graph;
        if (g == null) {
            g = new java.util.LinkedHashMap<>();
            root.put("graph", g);
        }
        Object co = g.get("components");
        List<Object> comps;
        if (co instanceof List<?> list) comps = (List<Object>) list;
        else {
            comps = new java.util.ArrayList<>();
            g.put("components", comps);
        }

        // Collect existing ids and find primary component index
        java.util.HashSet<String> used = new java.util.HashSet<>();
        int primaryIndex = -1;
        String primaryId = str(primary.get("id"), "").trim();
        for (int i = 0; i < comps.size(); i++) {
            Object it = comps.get(i);
            if (it instanceof Map<?, ?> cm) {
                String id = str(cm.get("id"), "").trim();
                if (!id.isEmpty()) used.add(id);
                if (id.equals(primaryId)) primaryIndex = i;
            }
        }

        if (primaryIndex < 0) {
            issues.add(warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_NOT_FOUND", "verticalProfile: 未在主组件列表中找到主组件 id=" + primaryId));
            return primary;
        }

        // Parse segments
        List<Segment> segments = new java.util.ArrayList<>();
        double baseW = pw;
        double baseD = pd;
        int totalHeight = 0;
        for (Object segObj : vpList) {
            if (!(segObj instanceof Map<?, ?> segMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> seg = (Map<String, Object>) segMap;
            Integer segH = intOrNull(seg.get("height"));
            if (segH == null || segH <= 0) continue;
            double scaleTop = d(seg.get("scaleTop"), d(seg.get("scale_top"), 1.0));
            if (scaleTop < 0.1) scaleTop = 0.1;
            if (scaleTop > 3.0) scaleTop = 3.0;
            segments.add(new Segment(segH, scaleTop));
            totalHeight += segH;
        }

        if (segments.isEmpty()) return primary;

        // Validate total height matches original (allow some tolerance)
        if (Math.abs(totalHeight - ph) > 2) {
            issues.add(warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_HEIGHT_MISMATCH", 
                "verticalProfile 总高度 (" + totalHeight + ") 与主组件高度 (" + ph + ") 不匹配，将使用分段高度"));
        }

        // Replace primary component with segments
        Object atObj = primary.get("at");
        int baseX = 0, baseY = 0, baseZ = 0;
        if (atObj instanceof Map<?, ?> atMap) {
            baseX = intOrNull(atMap.get("x")) != null ? intOrNull(atMap.get("x")) : 0;
            baseY = intOrNull(atMap.get("y")) != null ? intOrNull(atMap.get("y")) : 0;
            baseZ = intOrNull(atMap.get("z")) != null ? intOrNull(atMap.get("z")) : 0;
        }

        // Copy other properties from primary (material, facade, etc.)
        java.util.Map<String, Object> baseProps = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> e : primary.entrySet()) {
            String k = e.getKey();
            if (!k.equals("id") && !k.equals("type") && !k.equals("op") && !k.equals("w") && !k.equals("d") && !k.equals("h") && !k.equals("at")) {
                baseProps.put(k, e.getValue());
            }
        }

        // Generate segments
        List<Object> segmentComps = new java.util.ArrayList<>();
        int currentY = baseY;
        double currentW = baseW;
        double currentD = baseD;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            int segH = seg.height;
            double nextScale = seg.scaleTop;

            // Calculate segment dimensions (use current scale)
            int segW = Math.max(3, (int) Math.round(currentW));
            int segD = Math.max(3, (int) Math.round(currentD));
            double nextW = baseW * nextScale;
            double nextD = baseD * nextScale;

            String segId = (i == 0) ? primaryId : uniqueId(primaryId + "_Seg" + (i + 1), used);
            used.add(segId);

            Map<String, Object> segComp = new java.util.LinkedHashMap<>();
            segComp.put("id", segId);
            segComp.put("type", "SHELL_BOX");
            segComp.put("at", java.util.Map.of("x", baseX, "y", currentY, "z", baseZ));
            segComp.put("w", segW);
            segComp.put("d", segD);
            segComp.put("h", segH);
            segComp.putAll(baseProps); // Copy material, facade, etc.

            segmentComps.add(segComp);
            currentY += segH;
            currentW = nextW;
            currentD = nextD;
        }

        // Replace primary component with first segment, insert others after
        comps.set(primaryIndex, segmentComps.get(0));
        for (int i = 1; i < segmentComps.size(); i++) {
            comps.add(primaryIndex + i, segmentComps.get(i));
        }

        // Return first segment as new primary (for other macros to work on)
        @SuppressWarnings("unchecked")
        Map<String, Object> newPrimary = (Map<String, Object>) segmentComps.get(0);

        issues.add(warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE", 
            "verticalProfile 将主组件分解为 " + segments.size() + " 个分段 SHELL_BOX"));

        return newPrimary;
    }

    private static class Segment {
        final int height;
        final double scaleTop;

        Segment(int height, double scaleTop) {
            this.height = height;
            this.scaleTop = scaleTop;
        }
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

    private static void applyShapeType(Map<String, Object> primary, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
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

        switch (shape) {
            case "CIRCLE" -> {
                // Convert a SHELL_BOX-like body into a CYLINDER (best-effort).
                if (type.contains("SHELL_BOX") || type.contains("BOX_SHELL")) {
                    // r from w/d (if present)
                    Integer w = intOrNull(primary.get("w"));
                    Integer d = intOrNull(primary.get("d"));
                    int r = 6;
                    if (w != null || d != null) {
                        int ww = w != null ? w : d;
                        int dd = d != null ? d : w;
                        r = Math.max(2, Math.round((ww + dd) / 4.0f));
                    }
                    // preserve height
                    Integer h = intOrNull(primary.get("h"));
                    primary.put("type", "CYLINDER");
                    primary.remove("w");
                    primary.remove("d");
                    primary.put("r", r);
                    if (h != null) primary.put("h", h);
                    issues.add(warn("$.macro.shapeType", "W_MACRO_SHAPE", "shapeType=CIRCLE: converted main component from SHELL_BOX to CYLINDER (r=" + r + ")"));
                }
            }
            case "RECTANGLE" -> {
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
                    issues.add(warn("$.macro.shapeType", "W_MACRO_SHAPE", "shapeType=RECTANGLE: converted main component from CYLINDER to SHELL_BOX (w=d=" + w + ")"));
                }
            }
            case "HEXAGON", "HEXAGONAL" -> {
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
                        points.add(Map.of("x", px, "z", pz));
                    }

                    primary.put("type", "EXTRUDE_POLYGON");
                    primary.put("shape", "POINTS");
                    primary.put("points", points);
                    primary.put("h", h);
                    primary.remove("w");
                    primary.remove("d");
                    issues.add(warn("$.macro.shapeType", "W_MACRO_SHAPE", "shapeType=HEXAGON: converted main component from SHELL_BOX to EXTRUDE_POLYGON (r≈" + r + ")"));
                } else {
                    issues.add(warn("$.macro.shapeType", "W_MACRO_SHAPE_UNSUPPORTED", "shapeType=HEXAGON currently supports only SHELL_BOX-like primary components"));
                }
            }
            default ->
                // For now, just warn (future: HEXAGON -> EXTRUDE_POLYGON)
                    issues.add(warn("$.macro.shapeType", "W_MACRO_SHAPE_UNSUPPORTED", "shapeType unsupported: " + st));
        }
    }

    private static void applyHeightScale(Map<String, Object> primary, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        Object hs = macro.get("heightScale");
        if (hs == null) hs = macro.get("height_scale");
        if (hs == null) return;

        double scale = Double.NaN;
        if (hs instanceof Number n) scale = n.doubleValue();
        else {
            String s = String.valueOf(hs).trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) return;
            switch (s) {
                case "LOW", "SHORT" -> scale = 0.7;
                case "MEDIUM", "MID" -> scale = 1.0;
                case "HIGH", "TALL" -> scale = 1.6;
                default -> {
                    try {
                        scale = Double.parseDouble(s);
                    } catch (Exception ignored) {
                    }
                }
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
        issues.add(warn("$.macro.heightScale", "W_MACRO_HEIGHT", "heightScale applied to primary.h: " + h + " -> " + nh));
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
                String t = str(cm.get("type"), str(cm.get("op"), "")).trim().toUpperCase(Locale.ROOT);
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
            
            // Forma-Gene integration: support curvaturePower and cornerLift from macro
            Object cpObj = macro.get("roofCurvaturePower");
            if (cpObj == null) cpObj = macro.get("roof_curvature_power");
            if (cpObj != null) {
                try {
                    double cp = (cpObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(cpObj));
                    if (cp >= 0.1 && cp <= 3.0) roof.put("curvaturePower", cp);
                } catch (Exception ignored) {}
            }
            Object clObj = macro.get("roofCornerLift");
            if (clObj == null) clObj = macro.get("roof_corner_lift");
            if (clObj != null) {
                try {
                    double cl = (clObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(clObj));
                    if (cl >= 0.0 && cl <= 2.0) roof.put("cornerLift", cl);
                } catch (Exception ignored) {}
            }
            
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

    private static void applyOpenness(Map<String, Object> primary, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
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
                issues.add(warn("$.macro.openness" + ".facade.openings[" + i + "]", "W_MACRO_OPENNESS", "openness=" + o + ": rows/cols " + baseRows + "/" + baseCols + " -> " + rows + "/" + cols));
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


