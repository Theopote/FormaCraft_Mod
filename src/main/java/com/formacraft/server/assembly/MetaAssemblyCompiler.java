package com.formacraft.server.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MetaAssemblyCompiler (v1):
 * Compiles a higher-level "topology-ish" graph/components form into executable ops.
 * <p>
 * Input: BuildingSpec.extra.assembly
 * - If ops are present, compiler is unnecessary.
 * - If graph/components are present, compiler expands them into ops + PUSH_ORIGIN blocks.
 */
public final class MetaAssemblyCompiler {
    private static final int[][] DIR4 = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
    private MetaAssemblyCompiler() {}

    /**
     * Compile an assembly map into an AssemblySpec with an ops list.
     * Returns null if cannot compile.
     */
    @SuppressWarnings("unchecked")
    public static AssemblySpec compile(Object assemblyObj) {
        if (!(assemblyObj instanceof Map<?, ?> mm)) return null;
        Map<String, Object> m;
        try { m = (Map<String, Object>) mm; } catch (Exception e) { return null; }
        if (m.isEmpty()) return null;

        // If already has ops, just use it directly.
        AssemblySpec existing = AssemblySpec.fromExtra(m);
        if (existing != null && existing.ops != null && !existing.ops.isEmpty()) return existing;

        String paletteId = str(m.get("paletteId"), null);
        String entranceFacing = str(m.get("entranceFacing"), null);

        List<Map<String, Object>> ops = new ArrayList<>();

        Object graphObj = m.get("graph");
        Map<String, Object> graph = null;
        if (graphObj instanceof Map<?, ?> gm) {
            try { graph = (Map<String, Object>) gm; } catch (Exception ignored) {}
        }

        Object compsObj = (graph != null) ? graph.get("components") : m.get("components");
        if (!(compsObj instanceof List<?> comps)) return null;

                   // Build a lightweight component index for topology connections.
        // id -> component map (raw) + resolved local origin (x,y,z)
        Map<String, Map<String, Object>> byId = new HashMap<>();
        Map<String, int[]> originById = new HashMap<>();
        Map<String, Map<String, int[]>> portsById = new HashMap<>();

        for (Object c : comps) {
            if (!(c instanceof Map<?, ?> cm)) continue;
            Map<String, Object> comp;
            try { comp = (Map<String, Object>) cm; } catch (Exception e) { continue; }
            String id = str(comp.get("id"), null);
            int[] o = componentOrigin(comp);
            if (id != null && !id.isBlank()) {
                String cid = id.trim();
                byId.put(cid, comp);
                originById.put(cid, o);
                portsById.put(cid, buildPorts(comp));
            }
            emitComponent(ops, comp);
        }

        // Optional topology connections: connect endpoints by component id.
        Object connsObj = (graph != null) ? graph.get("connections") : m.get("connections");
        if (connsObj instanceof List<?> conns) {
            for (Object cc : conns) {
                if (!(cc instanceof Map<?, ?> cm)) continue;
                Map<String, Object> conn;
                try { conn = (Map<String, Object>) cm; } catch (Exception e) { continue; }
                emitConnection(ops, conn, byId, originById, portsById);
            }
        }

        if (ops.isEmpty()) return null;
        return AssemblySpec.of(paletteId, entranceFacing, ops);
    }

    private static void emitComponent(List<Map<String, Object>> ops, Map<String, Object> comp) {
        String type = str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT); // allow op-like components
        if (type.isBlank()) return;

        // placement (local offsets)
        int dx, dy, dz;
        Object at = comp.get("at");
        if (at instanceof Map<?, ?> am) {
            dx = i(am.get("x"), 0);
            dy = i(am.get("y"), 0);
            dz = i(am.get("z"), 0);
        } else {
            dx = i(comp.get("x"), 0);
            dy = i(comp.get("y"), 0);
            dz = i(comp.get("z"), 0);
        }

        ops.add(op("PUSH_ORIGIN", "dx", dx, "dy", dy, "dz", dz));

        // v1 component macros
        switch (type) {
            case "ANCHOR_FOOTPRINT", "FOOTPRINT_ANCHOR" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ANCHOR_FOOTPRINT");
                copyInt(comp, o, "x0", i(comp.get("x0"), 0));
                copyInt(comp, o, "x1", i(comp.get("x1"), 0));
                copyInt(comp, o, "z0", i(comp.get("z0"), 0));
                copyInt(comp, o, "z1", i(comp.get("z1"), 0));
                copyInt(comp, o, "yBase", i(comp.get("yBase"), i(comp.get("y"), 0)));
                copyInt(comp, o, "maxDepth", i(comp.get("maxDepth"), i(comp.get("anchorDepth"), 32)));
                copy(comp, o, "material");
                copy(comp, o, "stopOnSolid");
                copy(comp, o, "allowWaterEdit");
                copy(comp, o, "allowLavaEdit");
                ops.add(o);
            }
            case "ANCHORAGE", "ANCHORAGE_BLOCK" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ANCHORAGE");
                copyInt(comp, o, "w", i(comp.get("w"), i(comp.get("width"), 12)));
                copyInt(comp, o, "d", i(comp.get("d"), i(comp.get("depth"), 10)));
                copyInt(comp, o, "h", i(comp.get("h"), i(comp.get("height"), 8)));
                copyInt(comp, o, "yBase", i(comp.get("yBase"), i(comp.get("y"), 0)));
                copyInt(comp, o, "maxDepth", i(comp.get("maxDepth"), i(comp.get("anchorDepth"), 24)));
                copy(comp, o, "solid");
                copy(comp, o, "material");
                copy(comp, o, "carve");
                copy(comp, o, "allowWaterEdit");
                copy(comp, o, "allowLavaEdit");
                // detailing
                copyInt(comp, o, "topBevel", i(comp.get("topBevel"), i(comp.get("bevel"), 0)));
                copy(comp, o, "holes");
                copy(comp, o, "cableHoles");
                copyInt(comp, o, "guardWallHeight", i(comp.get("guardWallHeight"), i(comp.get("parapetHeight"), 0)));
                copyInt(comp, o, "guardWallInset", i(comp.get("guardWallInset"), 0));
                copy(comp, o, "guardWallCrenels");
                copy(comp, o, "crenels");
                copy(comp, o, "guardWallMaterial");
                copy(comp, o, "guardWall");
                ops.add(o);
            }
            case "TENSION_CABLE", "CABLE", "SAG_CABLE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "TENSION_CABLE");
                copy(comp, o, "from");
                copy(comp, o, "to");
                copyInt(comp, o, "sag", i(comp.get("sag"), i(comp.get("droop"), -1)));
                copyInt(comp, o, "samples", i(comp.get("samples"), i(comp.get("steps"), -1)));
                copyInt(comp, o, "thickness", i(comp.get("thickness"), 1));
                copy(comp, o, "material");
                copyInt(comp, o, "hangersEvery", i(comp.get("hangersEvery"), i(comp.get("hangerEvery"), 0)));
                copyInt(comp, o, "hangersToY", i(comp.get("hangersToY"), i(comp.get("hangerToY"), Integer.MIN_VALUE)));
                copy(comp, o, "hangersMaterial");
                copyInt(comp, o, "cableCount", i(comp.get("cableCount"), i(comp.get("count"), 1)));
                copyInt(comp, o, "cableSpacing", i(comp.get("cableSpacing"), i(comp.get("spacing"), 3)));
                copy(comp, o, "cableAxis");
                ops.add(o);
            }
            case "BUTTRESS", "FLYING_BUTTRESS" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "BUTTRESS");
                copy(comp, o, "from");
                copy(comp, o, "to");
                copyInt(comp, o, "rise", i(comp.get("rise"), i(comp.get("sagitta"), -1)));
                copyInt(comp, o, "samples", i(comp.get("samples"), i(comp.get("steps"), -1)));
                copyInt(comp, o, "thickness", i(comp.get("thickness"), 1));
                copyInt(comp, o, "pierDown", i(comp.get("pierDown"), i(comp.get("pier_down"), 6)));
                copy(comp, o, "rib");
                copy(comp, o, "pier");
                copy(comp, o, "joint");
                ops.add(o);
            }
            case "ARCH_RIB", "ARCH", "RIB_ARCH" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ARCH_RIB");
                copy(comp, o, "from");
                copy(comp, o, "to");
                copyInt(comp, o, "rise", i(comp.get("rise"), i(comp.get("sagitta"), -1)));
                copyInt(comp, o, "samples", i(comp.get("samples"), i(comp.get("steps"), -1)));
                copyInt(comp, o, "thickness", i(comp.get("thickness"), 1));
                copy(comp, o, "material");
                ops.add(o);
            }
            case "TRUSS_2D", "TRUSS", "TRUSS2D" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "TRUSS_2D");
                copy(comp, o, "from");
                copy(comp, o, "to");
                copyInt(comp, o, "height", i(comp.get("height"), i(comp.get("h"), 6)));
                copyInt(comp, o, "module", i(comp.get("module"), i(comp.get("step"), 4)));
                copy(comp, o, "pattern");
                copyInt(comp, o, "thickness", i(comp.get("thickness"), 1));
                copy(comp, o, "chord");
                copy(comp, o, "web");
                copy(comp, o, "joint");
                ops.add(o);
            }
            case "SPLINE_SWEEP", "SWEEP_SPLINE", "SPLINE_TUBE", "SPLINE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SPLINE_SWEEP");
                // points required
                copy(comp, o, "points");
                copy(comp, o, "profile");
                copy(comp, o, "profileFrame");
                copy(comp, o, "profileSnap");
                copy(comp, o, "frame");
                copy(comp, o, "snap");
                copyInt(comp, o, "profileW", i(comp.get("profileW"), i(comp.get("w"), 5)));
                copyInt(comp, o, "profileH", i(comp.get("profileH"), i(comp.get("h"), 3)));
                copy(comp, o, "profilePoints");
                copy(comp, o, "profileRings");
                copy(comp, o, "rings");
                copy(comp, o, "profileScale0");
                copy(comp, o, "profileScale1");
                copy(comp, o, "scale0");
                copy(comp, o, "scale1");
                // RECT taper (optional)
                copyInt(comp, o, "profileW0", i(comp.get("profileW0"), i(comp.get("w0"), Integer.MIN_VALUE)));
                copyInt(comp, o, "profileW1", i(comp.get("profileW1"), i(comp.get("w1"), Integer.MIN_VALUE)));
                copyInt(comp, o, "profileH0", i(comp.get("profileH0"), i(comp.get("h0"), Integer.MIN_VALUE)));
                copyInt(comp, o, "profileH1", i(comp.get("profileH1"), i(comp.get("h1"), Integer.MIN_VALUE)));
                copy(comp, o, "twistTurns");
                copy(comp, o, "twistPhase");
                copy(comp, o, "capEnds");
                copy(comp, o, "carveInterior");
                copyInt(comp, o, "capThickness", i(comp.get("capThickness"), 1));
                copy(comp, o, "connectSamples");
                copyInt(comp, o, "connectMaxStep", i(comp.get("connectMaxStep"), 2));
                // radius/taper
                copyInt(comp, o, "r", i(comp.get("r"), i(comp.get("radius"), 3)));
                copyInt(comp, o, "r0", i(comp.get("r0"), i(comp.get("radius0"), Integer.MIN_VALUE)));
                copyInt(comp, o, "r1", i(comp.get("r1"), i(comp.get("radius1"), Integer.MIN_VALUE)));
                copy(comp, o, "hollow");
                copyInt(comp, o, "thickness", 1);
                copyInt(comp, o, "samplesPerBlock", 10);
                copy(comp, o, "material");
                copy(comp, o, "wall");
                ops.add(o);
            }
            case "CYLINDER" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "CYLINDER");
                // allow r/radius, h/height
                copyInt(comp, o, "r", i(comp.get("r"), i(comp.get("radius"), 6)));
                copyInt(comp, o, "h", i(comp.get("h"), i(comp.get("height"), 18)));
                copy(comp, o, "hollow");
                copyInt(comp, o, "thickness", 1);
                copy(comp, o, "material");
                copy(comp, o, "wall");
                ops.add(o);
            }
            case "EXTRUDE_POLYGON", "EXTRUDE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "EXTRUDE_POLYGON");
                copy(comp, o, "shape");
                copyInt(comp, o, "w", 11);
                copyInt(comp, o, "d", 11);
                copyInt(comp, o, "h", i(comp.get("h"), i(comp.get("height"), 12)));
                copy(comp, o, "points");
                copy(comp, o, "hollow");
                copyInt(comp, o, "thickness", 1);
                copy(comp, o, "material");
                copy(comp, o, "wall");
                ops.add(o);
            }
            case "ROOF_COVER", "ROOF" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ROOF_COVER");
                // Roof shape kind (FLAT/GABLE). Component "type" is reserved for component kind.
                String roofType = str(
                        comp.get("roofType"),
                        str(comp.get("coverType"),
                                str(comp.get("cover"),
                                        str(comp.get("roof_cover_type"),
                                                str(comp.get("kind"), "GABLE")
                                        )
                                )
                        )
                );
                if (roofType != null) o.put("type", roofType);
                copyInt(comp, o, "w", 11);
                copyInt(comp, o, "d", 11);
                copyInt(comp, o, "y", 0);
                copyInt(comp, o, "overhang", 0);
                copyInt(comp, o, "rise", i(comp.get("rise"), i(comp.get("height"), 4)));
                copy(comp, o, "roof");
                copy(comp, o, "slab");
                ops.add(o);
            }
            case "CONNECTOR_LINE", "CONNECTOR" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "CONNECTOR_LINE");
                // from/to can be map or x0..x1 etc
                copy(comp, o, "from");
                copy(comp, o, "to");
                copyInt(comp, o, "x0", 0);
                copyInt(comp, o, "y0", 0);
                copyInt(comp, o, "z0", 0);
                copyInt(comp, o, "x1", 0);
                copyInt(comp, o, "y1", 0);
                copyInt(comp, o, "z1", 0);
                copyInt(comp, o, "thickness", 1);
                copyInt(comp, o, "h", i(comp.get("h"), i(comp.get("height"), 1)));
                copy(comp, o, "material");
                ops.add(o);
            }
            case "BOX_SHELL", "SHELL_BOX" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SHELL_BOX");
                int w = i(comp.get("w"), 15);
                int d = i(comp.get("d"), 15);
                int h = i(comp.get("h"), 18);
                o.put("w", w);
                o.put("d", d);
                o.put("h", h);
                copyInt(comp, o, "floorStep", 4);
                // optional block overrides
                copy(comp, o, "wall");
                copy(comp, o, "window");
                copy(comp, o, "floor");
                copy(comp, o, "roof");
                ops.add(o);

                // Optional facade attachments (unstyled):
                // - surface patterns (ribs/stripes/grid)
                // - openings (window grids / doors)
                emitFacadeOpsForBox(ops, comp, w, d, h);
            }
            case "BSP_INTERIOR", "BSP_FLOOR_PLAN" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "BSP_FLOOR_PLAN");
                copyInt(comp, o, "w", 19);
                copyInt(comp, o, "d", 19);
                copyInt(comp, o, "h", 30);
                Object cfg = comp.get("config");
                if (cfg == null) cfg = comp.get("floor_plan_logic");
                if (cfg == null) cfg = comp.get("floorPlanLogic");
                if (cfg != null) o.put("config", cfg);
                // optional semantic overrides
                copy(comp, o, "coreWall");
                copy(comp, o, "roomWall");
                copy(comp, o, "roomWallOpen");
                copy(comp, o, "stairs");
                ops.add(o);
            }
            case "CLEAR_BOX" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "CLEAR_BOX");
                copyInt(comp, o, "x0", 0);
                copyInt(comp, o, "y0", 0);
                copyInt(comp, o, "z0", 0);
                copyInt(comp, o, "x1", 0);
                copyInt(comp, o, "y1", 0);
                copyInt(comp, o, "z1", 0);
                ops.add(o);
            }
            default -> {
                // unknown component: ignore for forward compatibility
            }
        }

        ops.add(op("POP_ORIGIN"));
    }

    @SuppressWarnings("unchecked")
    private static void emitFacadeOpsForBox(List<Map<String, Object>> ops, Map<String, Object> comp, int w, int d, int h) {
        if (ops == null || comp == null) return;
        Object facadeObj = comp.get("facade");
        if (facadeObj == null) facadeObj = comp.get("facade_logic");
        if (facadeObj == null) facadeObj = comp.get("facadeLogic");
        if (facadeObj == null) facadeObj = comp; // allow top-level surfacePattern/openings

        if (!(facadeObj instanceof Map<?, ?> fm)) return;
        Map<String, Object> facade;
        try { facade = (Map<String, Object>) fm; } catch (Exception e) { return; }

        int hx = w / 2;
        int hz = d / 2;
        int y0 = 1;
        int y1 = Math.max(1, h - 1);

        // -------- surface pattern --------
        Object sp = facade.get("surfacePattern");
        if (sp == null) sp = facade.get("pattern");
        if (sp instanceof Map<?, ?> spm) {
            Map<String, Object> spx;
            try { spx = (Map<String, Object>) spm; } catch (Exception e) { spx = null; }
            String faces = str(spx.get("face"), str(spx.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
            String pattern = str(spx.get("type"), str(spx.get("pattern"), "GRID")).trim().toUpperCase(Locale.ROOT);
            int step = Math.max(1, i(spx.get("step"), i(spx.get("spacing"), 3)));
            int thickness = Math.max(1, i(spx.get("thickness"), 1));

            for (String face : expandFaces(faces)) {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SURFACE_PATTERN");
                o.put("face", face);
                o.put("pattern", pattern);
                o.put("step", step);
                o.put("thickness", thickness);
                // bounds
                o.put("x0", -hx); o.put("x1", hx);
                o.put("y0", y0);  o.put("y1", y1);
                o.put("z0", -hz); o.put("z1", hz);
                // material overrides: material/frame/accent accepted by engine pick()
                copy(spx, o, "material");
                copy(spx, o, "accent");
                copy(spx, o, "frame");
                ops.add(o);
            }
        }

        // -------- openings --------
        Object openingsObj = facade.get("openings");
        if (openingsObj == null) openingsObj = facade.get("opening");
        if (openingsObj instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> im)) continue;
                Map<String, Object> oo;
                try { oo = (Map<String, Object>) im; } catch (Exception e) { continue; }

                String faces = str(oo.get("face"), str(oo.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
                String kind = str(oo.get("kind"), str(oo.get("type"), "WINDOW_GRID")).trim().toUpperCase(Locale.ROOT);

                for (String face : expandFaces(faces)) {
                    Map<String, Object> o = new HashMap<>();
                    o.put("op", "OPENINGS");
                    o.put("face", face);
                    o.put("kind", kind);
                    // bounds
                    o.put("x0", -hx); o.put("x1", hx);
                    o.put("y0", 0);   o.put("y1", h);
                    o.put("z0", -hz); o.put("z1", hz);
                    // sizing knobs
                    copyInt(oo, o, "rows", i(oo.get("rows"), 2));
                    copyInt(oo, o, "cols", i(oo.get("cols"), 3));
                    copyInt(oo, o, "winW", i(oo.get("winW"), i(oo.get("w"), 2)));
                    copyInt(oo, o, "winH", i(oo.get("winH"), i(oo.get("h"), 3)));
                    copyInt(oo, o, "sillY", i(oo.get("sillY"), 2));
                    copyInt(oo, o, "marginX", i(oo.get("marginX"), 2));
                    copyInt(oo, o, "marginY", i(oo.get("marginY"), 2));
                    copyInt(oo, o, "gapX", i(oo.get("gapX"), 2));
                    copyInt(oo, o, "gapY", i(oo.get("gapY"), 2));
                    copyInt(oo, o, "frameThickness", i(oo.get("frameThickness"), 1));
                    copyInt(oo, o, "mullionStep", i(oo.get("mullionStep"), 0));
                    // arch/rose parameters (optional)
                    copy(oo, o, "archType");
                    copy(oo, o, "arch");
                    copyInt(oo, o, "archThickness", i(oo.get("archThickness"), i(oo.get("archT"), 0)));
                    copy(oo, o, "keystone");
                    copy(oo, o, "keystoneOn");
                    copy(oo, o, "tracery");
                    copy(oo, o, "traceryType");
                    copy(oo, o, "traceryMaterial");
                    copyInt(oo, o, "traceryThickness", i(oo.get("traceryThickness"), i(oo.get("traceryT"), 0)));
                    copyInt(oo, o, "traceryY", i(oo.get("traceryY"), Integer.MIN_VALUE));
                    copyInt(oo, o, "traceryInset", i(oo.get("traceryInset"), 0));
                    copyInt(oo, o, "traceryFoilRadius", i(oo.get("traceryFoilRadius"), i(oo.get("foilRadius"), 0)));
                    copyInt(oo, o, "traceryFoilCenterY", i(oo.get("traceryFoilCenterY"), i(oo.get("foilCenterY"), Integer.MIN_VALUE)));
                    copyInt(oo, o, "traceryFoilCount", i(oo.get("traceryFoilCount"), i(oo.get("foilCount"), 1)));
                    copyInt(oo, o, "traceryFoilStepY", i(oo.get("traceryFoilStepY"), i(oo.get("foilStepY"), i(oo.get("foilGapY"), 0))));
                    // Also copy raw (string-friendly) knobs so LLM can use e.g. foilCount="AUTO"
                    copy(oo, o, "foilCount");
                    copy(oo, o, "traceryFoilCount");
                    copy(oo, o, "foilStepY");
                    copy(oo, o, "foilGapY");
                    copy(oo, o, "traceryFoilStepY");
                    copy(oo, o, "foilCenterY");
                    copy(oo, o, "traceryFoilCenterY");
                    copyInt(oo, o, "r", i(oo.get("r"), i(oo.get("radius"), 0)));
                    copyInt(oo, o, "centerY", i(oo.get("centerY"), -999999));
                    copyInt(oo, o, "petals", i(oo.get("petals"), i(oo.get("spokes"), 0)));
                    copyInt(oo, o, "ring", i(oo.get("ring"), 0));
                    copy(oo, o, "phase");
                    copy(oo, o, "phi");
                    copyInt(oo, o, "spokeWidth", i(oo.get("spokeWidth"), i(oo.get("spokeW"), 0)));
                    copy(oo, o, "spokeThreshold");
                    copy(oo, o, "spokeThresh");
                    copy(oo, o, "innerFill");
                    copy(oo, o, "spokeMaterial");
                    // materials
                    copy(oo, o, "fill");
                    copy(oo, o, "frame");
                    copy(oo, o, "air"); // allow override of "air" behavior (e.g., open hole)
                    ops.add(o);
                }
            }
        }

        // -------- facade grid (curtain wall macro) --------
        Object fg = facade.get("facadeGrid");
        if (fg == null) fg = facade.get("FACADE_GRID");
        if (fg == null) fg = facade.get("curtainWall");
        if (fg instanceof Map<?, ?> fgm) {
            Map<String, Object> fgx;
            try { fgx = (Map<String, Object>) fgm; } catch (Exception e) { fgx = null; }
            String faces = str(fgx.get("face"), str(fgx.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
            int bayW = Math.max(1, i(fgx.get("bayW"), i(fgx.get("moduleW"), i(fgx.get("gridW"), 3))));
            int bayH = Math.max(1, i(fgx.get("bayH"), i(fgx.get("moduleH"), i(fgx.get("gridH"), 4))));
            int mullionT = Math.max(0, i(fgx.get("mullionThickness"), i(fgx.get("mullionT"), 1)));
            int transomT = Math.max(0, i(fgx.get("transomThickness"), i(fgx.get("transomT"), 1)));
            int borderT = Math.max(0, i(fgx.get("borderThickness"), i(fgx.get("borderT"), mullionT)));
            int marginU = Math.max(0, i(fgx.get("marginU"), i(fgx.get("marginX"), 1)));
            int marginY = Math.max(0, i(fgx.get("marginY"), 1));
            int inset = Math.max(0, i(fgx.get("inset"), 0));
            int depth = Math.max(1, i(fgx.get("depth"), 1));
            // spandrel zones (optional)
            int spEvery = Math.max(0, i(fgx.get("spandrelEvery"), i(fgx.get("spEvery"), 0)));
            int spH = Math.max(0, i(fgx.get("spandrelHeight"), i(fgx.get("spH"), 0)));
            int spOff = Math.max(0, i(fgx.get("spandrelOffset"), i(fgx.get("spOffset"), 0)));

            for (String face : expandFaces(faces)) {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "FACADE_GRID");
                o.put("face", face);
                // bounds
                o.put("x0", -hx); o.put("x1", hx);
                o.put("y0", y0);  o.put("y1", y1);
                o.put("z0", -hz); o.put("z1", hz);
                // knobs
                o.put("bayW", bayW);
                o.put("bayH", bayH);
                o.put("mullionThickness", mullionT);
                o.put("transomThickness", transomT);
                o.put("borderThickness", borderT);
                o.put("marginU", marginU);
                o.put("marginY", marginY);
                o.put("inset", inset);
                o.put("depth", depth);
                o.put("spandrelEvery", spEvery);
                o.put("spandrelHeight", spH);
                o.put("spandrelOffset", spOff);
                // materials
                copy(fgx, o, "frame");
                copy(fgx, o, "fill");
                copy(fgx, o, "spandrelFill");
                copy(fgx, o, "material"); // allow "material" alias for frame in engine pick()
                ops.add(o);
            }
        }

        // -------- surface bands (cornice/belt-lines/columns) --------
        Object sb = facade.get("surfaceBands");
        if (sb == null) sb = facade.get("SURFACE_BANDS");
        if (sb == null) sb = facade.get("bands");
        if (sb instanceof Map<?, ?> sbm) {
            Map<String, Object> sbx;
            try { sbx = (Map<String, Object>) sbm; } catch (Exception e) { sbx = null; }
            String faces = str(sbx.get("face"), str(sbx.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
            Object hb = sbx.get("horizontalBands");
            if (hb == null) hb = sbx.get("hBands");
            if (hb == null) hb = sbx.get("bandsH");
            Object vb = sbx.get("verticalBands");
            if (vb == null) vb = sbx.get("vBands");
            if (vb == null) vb = sbx.get("bandsV");

            for (String face : expandFaces(faces)) {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SURFACE_BANDS");
                o.put("face", face);
                // bounds
                o.put("x0", -hx); o.put("x1", hx);
                o.put("y0", y0);  o.put("y1", y1);
                o.put("z0", -hz); o.put("z1", hz);
                // transmit band lists verbatim (engine will parse each band entry)
                if (hb != null) o.put("horizontalBands", hb);
                if (vb != null) o.put("verticalBands", vb);
                ops.add(o);
            }
        }
    }

    private static List<String> expandFaces(String faces) {
        if (faces == null) return List.of("NORTH", "SOUTH", "EAST", "WEST");
        String f = faces.trim().toUpperCase(Locale.ROOT);
        if (f.isBlank() || f.equals("ALL") || f.equals("*")) return List.of("NORTH", "SOUTH", "EAST", "WEST");
        // allow comma-separated
        if (f.contains(",")) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (String s : f.split(",")) {
                String x = s.trim().toUpperCase(Locale.ROOT);
                if (x.isBlank()) continue;
                if (x.equals("ALL") || x.equals("*")) return List.of("NORTH", "SOUTH", "EAST", "WEST");
                out.add(x);
            }
            return out.isEmpty() ? List.of("NORTH", "SOUTH", "EAST", "WEST") : out;
        }
        return List.of(f);
    }

    private static void emitConnection(List<Map<String, Object>> ops,
                                       Map<String, Object> conn,
                                       Map<String, Map<String, Object>> byId,
                                       Map<String, int[]> originById,
                                       Map<String, Map<String, int[]>> portsById) {
        if (conn == null || conn.isEmpty()) return;
        String type = str(conn.get("type"), "CONNECTOR_LINE").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = "CONNECTOR_LINE";

        Endpoint a = parseEndpoint(conn.get("from"));
        Endpoint b = parseEndpoint(conn.get("to"));
        if (a == null || b == null) return;

        // Auto-rewrite spline endpoints: if user references start/end/entrance/exit/in/out without direction,
        // and the component is a SPLINE, rewrite to start_{n/e/s/w} / end_{n/e/s/w} (tangent-direction port).
        a = rewriteSplineTangentPort(a, byId, portsById);
        b = rewriteSplineTangentPort(b, byId, portsById);

        int[] p0 = resolveEndpoint(a, byId, originById, portsById);
        int[] p1 = resolveEndpoint(b, byId, originById, portsById);
        if (p0 == null || p1 == null) return;

        // Expand connection type to corresponding execution op.
        // v1 supports:
        // - CONNECTOR_LINE (beam)
        // - PATH (road)
        // - WALL (wall section)
        // - BRIDGE (bridge span)
        String opName = switch (type) {
            case "PATH", "ROAD" -> "PATH_ROUTE";
            case "WALL" -> "WALL_ROUTE";
            case "BRIDGE" -> "BRIDGE_ROUTE";
            default -> "CONNECTOR_LINE";
        };

        // Routing mode for avoid:
        // - routing: "ASTAR" enables a small 2D grid A* router to avoid boxes
        // - avoidAStar: boolean shorthand
        boolean useAStar = false;
        Object routing = conn.get("routing");
        if (routing != null) {
            String rs = String.valueOf(routing).trim().toUpperCase(Locale.ROOT);
            useAStar = rs.contains("ASTAR") || rs.contains("A*") || rs.contains("A_STAR");
        }
        if (!useAStar) {
            Object aa = conn.get("avoidAStar");
            if (aa instanceof Boolean b2) useAStar = b2;
            else if (aa != null) {
                String s = String.valueOf(aa).trim().toLowerCase(Locale.ROOT);
                useAStar = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
            }
        }

        // via[] routing: expand one logical connection into multiple segments.
        // via items support:
        // - "A.port" / {component,port,...}
        // - {x,y,z} (local coords)
        List<int[]> chain = new ArrayList<>();
        chain.add(p0);
        Object viaObj = conn.get("via");
        List<int[]> userVia = new ArrayList<>();
        if (viaObj instanceof List<?> via) {
            for (Object v : via) {
                int[] wp = resolveWaypoint(v, byId, originById, portsById);
                if (wp != null) userVia.add(wp);
            }
        }

        // avoid[] / avoidComponents / avoidAllComponents: derive avoid rects for routing utilities
        List<Rect2> avoids = parseAvoids(conn.get("avoid"));
        avoids.addAll(parseAvoidComponents(conn, a.componentId, b.componentId, byId, originById));

        // Lead-out / lead-in: encourage routes to leave/arrive along the port direction.
        // - routingAutoLead=true sets defaults (leadOut=3, leadIn=2) unless explicitly provided.
        int leadOut = i(conn.get("routingLeadOut"), 0);
        int leadIn = i(conn.get("routingLeadIn"), 0);
        boolean autoLead = false;
        Object al = conn.get("routingAutoLead");
        if (al instanceof Boolean bb) autoLead = bb;
        else if (al != null) {
            String s = String.valueOf(al).trim().toLowerCase(Locale.ROOT);
            autoLead = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }
        if (autoLead) {
            if (conn.get("routingLeadOut") == null) leadOut = 3;
            if (conn.get("routingLeadIn") == null) leadIn = 2;
        }
        leadOut = Math.max(0, Math.min(32, leadOut));
        leadIn = Math.max(0, Math.min(32, leadIn));

        boolean leadSoft = useAStar;
        Object ls = conn.get("routingLeadSoft");
        if (ls instanceof Boolean bb2) leadSoft = bb2;
        else if (ls != null) {
            String s = String.valueOf(ls).trim().toLowerCase(Locale.ROOT);
            leadSoft = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }

        // routingLeadHard: force explicit lead-out/lead-in waypoints (a short straight "landing") even when using A*.
        // This is helpful for connecting to spline endpoints where we inferred a tangent-direction port.
        // Default: true when routingAutoLead is enabled AND either endpoint uses a directional prefixed port (start_* / end_*).
        Boolean leadHard = null;
        Object lh = conn.get("routingLeadHard");
        if (lh instanceof Boolean bb3) leadHard = bb3;
        else if (lh != null) {
            String s = String.valueOf(lh).trim().toLowerCase(Locale.ROOT);
            leadHard = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }
        if (leadHard == null) {
            String ap = normalizePortKey(a.port);
            String bp = normalizePortKey(b.port);
            boolean directionalPrefixed = (ap.startsWith("start_") || ap.startsWith("end_") || bp.startsWith("start_") || bp.startsWith("end_"));
            leadHard = autoLead && directionalPrefixed;
        }
        boolean effectiveLeadSoft = leadSoft && !leadHard;

        // If not using A* soft lead (or leadHard=true), insert lead points as explicit via.
        if (!(useAStar && effectiveLeadSoft)) {
            int[] lead0 = (leadOut > 0) ? computeLeadPoint(p0, a.port, leadOut, avoids) : null;
            int[] lead1 = (leadIn > 0) ? computeLeadPoint(p1, b.port, leadIn, avoids) : null;
            if (lead0 != null) chain.add(lead0);
            chain.addAll(userVia);
            if (lead1 != null) chain.add(lead1);
        } else {
            chain.addAll(userVia);
        }
        chain.add(p1);

        // A* routing knobs (safe defaults)
        int routingPadDefault = 6;
        long routingMaxAreaDefault = 80000L;
        int routingMaxNodesDefault = 0; // 0 means auto (scaled with area)
        // routingStyle macro: PLANNED / ORGANIC, plus routingStyleStrength (0..1) to interpolate ORGANIC->PLANNED.
        // This only changes defaults; explicit params always win.
        String routingStyle = str(conn.get("routingStyle"), "").trim().toUpperCase(Locale.ROOT);
        double routingStyleStrength = d(conn.get("routingStyleStrength"));
        if (!Double.isNaN(routingStyleStrength)) {
            if (routingStyleStrength < 0.0) routingStyleStrength = 0.0;
            if (routingStyleStrength > 1.0) routingStyleStrength = 1.0;
        }
        int preferStraightDefault = 1;
        int preferAxisWeightDefault = 1;
        String preferAxisDefault = "AUTO";
        boolean preferDoorAxisDefault = false;
        Integer leadWeightDefaultOverride = null;
        Integer leadOutWeightDefaultOverride = null;
        Integer leadInWeightDefaultOverride = null;

        // Endpoints for continuous style:
        // ORGANIC (0) -> PLANNED (1)
        final int organicPreferStraight = 0;
        final int plannedPreferStraight = 3;
        final int organicPreferAxisWeight = 0;
        final int plannedPreferAxisWeight = 3;
        final int organicLeadWeight = 1;
        final int plannedLeadWeight = 6;
        final int organicLeadOutWeight = 1;
        final int plannedLeadOutWeight = 7;
        final int organicLeadInWeight = 1;
        final int plannedLeadInWeight = 5;
        if ("PLANNED".equals(routingStyle)) {
            preferStraightDefault = 3;
            preferAxisWeightDefault = 3;
            preferAxisDefault = "AUTO";
            preferDoorAxisDefault = true;
            leadWeightDefaultOverride = 6;
            leadOutWeightDefaultOverride = 7;
            leadInWeightDefaultOverride = 5;
            routingPadDefault = 8;
            routingMaxAreaDefault = 140000L;
            routingMaxNodesDefault = 0;
        } else if ("ORGANIC".equals(routingStyle)) {
            preferStraightDefault = 0;
            preferAxisWeightDefault = 0;
            preferAxisDefault = "NONE";
            preferDoorAxisDefault = false;
            leadWeightDefaultOverride = 1;
            leadOutWeightDefaultOverride = 1;
            leadInWeightDefaultOverride = 1;
            routingPadDefault = 4;
            routingMaxAreaDefault = 60000L;
            routingMaxNodesDefault = 20000; // cap for speed; 0=auto can be expensive on big areas
        }

        // If strength is provided, it overrides routingStyle defaults (still does not override explicit params).
        if (!Double.isNaN(routingStyleStrength)) {
            preferStraightDefault = (int) Math.round(organicPreferStraight + (plannedPreferStraight - organicPreferStraight) * routingStyleStrength);
            preferAxisWeightDefault = (int) Math.round(organicPreferAxisWeight + (plannedPreferAxisWeight - organicPreferAxisWeight) * routingStyleStrength);
            // Axis preference is categorical; use a small threshold to keep true ORGANIC behaving axis-free.
            preferAxisDefault = (routingStyleStrength <= 0.10) ? "NONE" : "AUTO";
            // Door-axis preference is boolean; threshold at 0.5.
            preferDoorAxisDefault = (routingStyleStrength >= 0.50);
            leadWeightDefaultOverride = (int) Math.round(organicLeadWeight + (plannedLeadWeight - organicLeadWeight) * routingStyleStrength);
            leadOutWeightDefaultOverride = (int) Math.round(organicLeadOutWeight + (plannedLeadOutWeight - organicLeadOutWeight) * routingStyleStrength);
            leadInWeightDefaultOverride = (int) Math.round(organicLeadInWeight + (plannedLeadInWeight - organicLeadInWeight) * routingStyleStrength);

            routingPadDefault = (int) Math.round(4 + (8 - 4) * routingStyleStrength);
            routingMaxAreaDefault = Math.round(60000L + (140000L - 60000L) * routingStyleStrength);
            // Let very PLANNED routes use auto-scaling nodes (0), otherwise cap for speed.
            routingMaxNodesDefault = (routingStyleStrength >= 0.85) ? 0 : (int) Math.round(20000 + (-20000) * routingStyleStrength);
        }

        // routingQoS macro: FAST / BALANCED / ROBUST. Only affects defaults; explicit params always win.
        String routingQoS = str(conn.get("routingQoS"), "").trim().toUpperCase(Locale.ROOT);
        if (routingQoS.isEmpty()) {
            // Auto QoS based on style strength: organic can be fast, planned can spend more time to be robust.
            if (!Double.isNaN(routingStyleStrength)) {
                if (routingStyleStrength < 0.35) routingQoS = "FAST";
                else if (routingStyleStrength < 0.75) routingQoS = "BALANCED";
                else routingQoS = "ROBUST";
            } else if ("PLANNED".equals(routingStyle)) {
                routingQoS = "ROBUST";
            } else if ("ORGANIC".equals(routingStyle)) {
                routingQoS = "FAST";
            }
        }
        switch (routingQoS) {
            case "FAST" -> {
                routingPadDefault = 4;
                routingMaxAreaDefault = Math.min(routingMaxAreaDefault, 60000L);
                routingMaxNodesDefault = 16000;
            }
            case "BALANCED" -> {
                routingPadDefault = 6;
                routingMaxAreaDefault = Math.min(routingMaxAreaDefault, 90000L);
                routingMaxNodesDefault = 0; // allow auto
            }
            case "ROBUST" -> {
                routingPadDefault = 10;
                routingMaxAreaDefault = Math.max(routingMaxAreaDefault, 160000L);
                routingMaxNodesDefault = 0; // allow auto
            }
        }

        int routingPad = Math.max(0, i(conn.get("routingPad"), routingPadDefault));
        long routingMaxArea = Math.max(2000L, i(conn.get("routingMaxArea"), (int) routingMaxAreaDefault));
        int routingMaxNodesRaw = i(conn.get("routingMaxNodes"), routingMaxNodesDefault);
        int routingMaxNodes = (routingMaxNodesRaw == 0) ? 0 : Math.max(2000, routingMaxNodesRaw); // preserve 0=auto

        int preferStraight = Math.max(0, i(conn.get("routingPreferStraight"), preferStraightDefault)); // turn penalty weight

        // Soft-lead weight: higher => stronger tendency to leave/arrive along port axis.
        // Default ties to preferStraight (unless routingStyle overrides it) so "more planned" routes also respect door axes.
        int leadWeightDefault = (leadWeightDefaultOverride != null) ? leadWeightDefaultOverride : Math.max(1, 2 + preferStraight);
        int leadWeight = i(conn.get("routingLeadWeight"), leadWeightDefault);
        int leadOutWeight = i(conn.get("routingLeadOutWeight"), (leadOutWeightDefaultOverride != null) ? leadOutWeightDefaultOverride : leadWeight);
        int leadInWeight = i(conn.get("routingLeadInWeight"), (leadInWeightDefaultOverride != null) ? leadInWeightDefaultOverride : leadWeight);
        if (leadOutWeight < 0) leadOutWeight = 0;
        if (leadInWeight < 0) leadInWeight = 0;

        // Prefer axis (X/Z/AUTO/NONE) + weight.
        String preferAxis = str(conn.get("routingPreferAxis"), preferAxisDefault).trim().toUpperCase(Locale.ROOT);
        int preferAxisWeight = Math.max(0, i(conn.get("routingPreferAxisWeight"), preferAxisWeightDefault));
        boolean preferDoorAxis = preferDoorAxisDefault;
        Object pda = conn.get("routingPreferDoorAxis");
        if (pda instanceof Boolean b3) preferDoorAxis = b3;
        else if (pda != null) {
            String s = String.valueOf(pda).trim().toLowerCase(Locale.ROOT);
            preferDoorAxis = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }
        if (preferDoorAxis) {
            String ax = axisFromPort(a.port);
            if (ax != null) preferAxis = ax;
        }

        // Soft-lead ring metric:
        // - STEPS: use A* gScore (true path step depth; better under detours)
        // - MANHATTAN: use |dx|+|dz| (legacy-ish)
        String leadRing = str(conn.get("routingLeadRing"), "STEPS").trim().toUpperCase(Locale.ROOT);
        if (!"MANHATTAN".equals(leadRing)) leadRing = "STEPS";
        int leadInStepsMaxNodes = i(conn.get("routingLeadInStepsMaxNodes"), 0);
        if (leadInStepsMaxNodes < 0) leadInStepsMaxNodes = 0;

        // avoid routing: if any segment crosses an avoid rect, auto-insert detours.
        if (!avoids.isEmpty()) {
            List<int[]> expanded = new ArrayList<>();
            expanded.add(chain.getFirst());
            for (int i = 0; i + 1 < chain.size(); i++) {
                int[] pA = expanded.getLast();
                int[] pB = chain.get(i + 1);
                int leadOutSeg = (useAStar && effectiveLeadSoft && i == 0) ? leadOut : 0;
                int leadInSeg = (useAStar && effectiveLeadSoft && i + 2 == chain.size()) ? leadIn : 0;
                List<int[]> detour = computeDetour(pA, pB, avoids, useAStar, routingPad, routingMaxArea, routingMaxNodes, preferStraight, preferAxis, preferAxisWeight,
                        leadOutSeg, leadInSeg, a.port, b.port, leadOutWeight, leadInWeight, leadRing, leadInStepsMaxNodes);
                expanded.addAll(detour);
            }
            chain = expanded;
        }

        for (int i = 0; i + 1 < chain.size(); i++) {
            int[] a0 = chain.get(i);
            int[] a1 = chain.get(i + 1);
            Map<String, Object> o = new HashMap<>();
            o.put("op", opName);
            o.put("from", Map.of("x", a0[0], "y", a0[1], "z", a0[2]));
            o.put("to", Map.of("x", a1[0], "y", a1[1], "z", a1[2]));
            copyInt(conn, o, "thickness", 1);
            copyInt(conn, o, "h", i(conn.get("h"), i(conn.get("height"), 1)));
            copy(conn, o, "material");

            // optional route params
            copyInt(conn, o, "width", i(conn.get("width"), 3));          // PATH/BRIDGE
            copyInt(conn, o, "wallHeight", i(conn.get("wallHeight"), 10)); // WALL
            copyInt(conn, o, "wallThickness", i(conn.get("wallThickness"), i(conn.get("thickness"), 5))); // WALL
            copyInt(conn, o, "foundationDepth", i(conn.get("foundationDepth"), 3)); // WALL
            copyInt(conn, o, "maxStep", i(conn.get("maxStep"), 1));       // PATH/WALL smoothing
            copy(conn, o, "terrainAdaptation");                            // passthrough hints

            // Provide sane defaults so LLM can omit terrain genes.
            if (!o.containsKey("terrainAdaptation") || o.get("terrainAdaptation") == null) {
                switch (opName) {
                    case "PATH_ROUTE" -> o.put("terrainAdaptation", Map.of(
                            "mode", "DRAPE",
                            "max_step_height", 1,
                            "foundation_depth", 2,
                            "clearHeight", 2
                    ));
                    case "WALL_ROUTE" -> o.put("terrainAdaptation", Map.of(
                            "mode", "DRAPE",
                            "max_step_height", 1,
                            "foundation_depth", 3
                    ));
                    case "BRIDGE_ROUTE" -> o.put("terrainAdaptation", Map.of(
                            "mode", "ANCHOR",
                            "anchorMaxDepth", 64
                    ));
                }
            }
            ops.add(o);
        }
    }

    private record Endpoint(String componentId, String port, String anchor, int dx, int dy, int dz) {}

    private static Endpoint rewriteSplineTangentPort(Endpoint e,
                                                     Map<String, Map<String, Object>> byId,
                                                     Map<String, Map<String, int[]>> portsById) {
        if (e == null || e.componentId == null || e.componentId.isBlank()) return e;
        if (e.port == null || e.port.isBlank()) return e;
        if (byId == null || portsById == null) return e;

        Map<String, Object> comp = byId.get(e.componentId);
        if (comp == null) return e;
        String type = str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);
        if (!type.contains("SPLINE")) return e;

        String p = normalizePortKey(e.port);
        // If already directional (e.g., start_east) don't touch it.
        if (p.startsWith("start_") || p.startsWith("end_")) return e;

        String kind = null; // "start" or "end"
        if (p.equals("start") || p.equals("entrance") || p.equals("in")) kind = "start";
        if (p.equals("end") || p.equals("exit") || p.equals("out")) kind = "end";
        if (kind == null) return e;

        Map<String, int[]> ports = portsById.get(e.componentId);
        if (ports == null || ports.isEmpty()) return e;

        // We generate at most one of these, but check all 4 for robustness/user overrides.
        String[] dirs = new String[]{"north", "south", "east", "west"};
        for (String d : dirs) {
            String k = kind + "_" + d;
            if (ports.containsKey(k)) {
                return new Endpoint(e.componentId, k, e.anchor, e.dx, e.dy, e.dz);
            }
        }
        return e;
    }

    private static Endpoint parseEndpoint(Object v) {
        switch (v) {
            case null -> {
                return null;
            }
            case String s -> {
                String id = s.trim();
                if (id.isEmpty()) return null;
                // allow "A.south"
                int dot = id.indexOf('.');
                if (dot > 0 && dot < id.length() - 1) {
                    String cid = id.substring(0, dot).trim();
                    String port = id.substring(dot + 1).trim();
                    if (!cid.isEmpty() && !port.isEmpty()) return new Endpoint(cid, port, "CENTER", 0, 0, 0);
                }
                return new Endpoint(id, null, "CENTER", 0, 0, 0);
            }
            case Map<?, ?> mm -> {
                String id = str(mm.get("component"), null);
                if (id == null) id = str(mm.get("id"), null);
                if (id == null || id.isBlank()) return null;
                String port = str(mm.get("port"), null);
                String anchor = str(mm.get("anchor"), "CENTER").trim().toUpperCase(Locale.ROOT);
                int dx = i(mm.get("x"), 0);
                int dy = i(mm.get("y"), 0);
                int dz = i(mm.get("z"), 0);
                return new Endpoint(id.trim(), port != null && !port.isBlank() ? port.trim() : null, anchor, dx, dy, dz);
            }
            default -> {
            }
        }
        return null;
    }

    // =============================================================================================
    // Avoid routing helpers (2D, XZ)
    // =============================================================================================

    private record Rect2(int xMin, int zMin, int xMax, int zMax) {}

    private static List<Rect2> parseAvoids(Object v) {
        List<Rect2> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object it : list) {
            if (!(it instanceof Map<?, ?> m)) continue;
            int x0 = i(m.get("x0"), 0);
            int z0 = i(m.get("z0"), 0);
            int x1 = i(m.get("x1"), 0);
            int z1 = i(m.get("z1"), 0);
            int margin = Math.max(0, i(m.get("margin"), 2));
            int minX = Math.min(x0, x1) - margin;
            int maxX = Math.max(x0, x1) + margin;
            int minZ = Math.min(z0, z1) - margin;
            int maxZ = Math.max(z0, z1) + margin;
            out.add(new Rect2(minX, minZ, maxX, maxZ));
        }
        return out;
    }

    private static List<Rect2> parseAvoidComponents(Map<String, Object> conn,
                                                    String fromComponentId,
                                                    String toComponentId,
                                                    Map<String, Map<String, Object>> byId,
                                                    Map<String, int[]> originById) {
        List<Rect2> out = new ArrayList<>();
        if (conn == null) return out;

        int margin = Math.max(0, i(conn.get("avoidMargin"), 3));
        String fromId = fromComponentId != null ? fromComponentId.trim() : "";
        String toId = toComponentId != null ? toComponentId.trim() : "";

        // Include explicit lists
        java.util.Set<String> include = new java.util.HashSet<>();
        Object v = conn.get("avoidComponents");
        if (v instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String id = String.valueOf(it).trim();
                if (!id.isEmpty()) include.add(id);
            }
        }
        Object v2 = conn.get("avoidIncludeComponents");
        if (v2 instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String id = String.valueOf(it).trim();
                if (!id.isEmpty()) include.add(id);
            }
        }

        // Exclude list
        java.util.Set<String> exclude = new java.util.HashSet<>();
        Object ex = conn.get("avoidExcludeComponents");
        if (ex instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String id = String.valueOf(it).trim();
                if (!id.isEmpty()) exclude.add(id);
            }
        }
        if (!fromId.isEmpty()) exclude.add(fromId);
        if (!toId.isEmpty()) exclude.add(toId);

        boolean avoidAll = false;
        Object aa = conn.get("avoidAllComponents");
        if (aa instanceof Boolean b) avoidAll = b;
        else if (aa != null) {
            String s = String.valueOf(aa).trim().toLowerCase(Locale.ROOT);
            avoidAll = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }

        // If avoidAllComponents, include every known component id (except excluded)
        if (avoidAll && byId != null) {
            for (String id : byId.keySet()) {
                if (id == null) continue;
                String cid = id.trim();
                if (cid.isEmpty()) continue;
                include.add(cid);
            }
        }

        // Build rects for included ids minus excluded
        for (String id : include) {
            if (id == null) continue;
            String cid = id.trim();
            if (cid.isEmpty() || exclude.contains(cid)) continue;
            Map<String, Object> comp = byId != null ? byId.get(cid) : null;
            int[] o = originById != null ? originById.get(cid) : null;
            if (comp == null || o == null) continue;
            Rect2 r = inferComponentRect2(comp, o[0], o[2], margin);
            if (r != null) out.add(r);
        }

        return out;
    }

    private static Rect2 inferComponentRect2(Map<String, Object> comp, int ox, int oz, int margin) {
        if (comp == null) return null;
        // Optional explicit bbox override
        Object bbObj = comp.get("bbox");
        if (bbObj instanceof Map<?, ?> m) {
            int x0 = i(m.get("x0"), 0);
            int z0 = i(m.get("z0"), 0);
            int x1 = i(m.get("x1"), 0);
            int z1 = i(m.get("z1"), 0);
            int mgn = Math.max(margin, i(m.get("margin"), 0));
            int minX = Math.min(x0, x1) + ox - mgn;
            int maxX = Math.max(x0, x1) + ox + mgn;
            int minZ = Math.min(z0, z1) + oz - mgn;
            int maxZ = Math.max(z0, z1) + oz + mgn;
            return new Rect2(minX, minZ, maxX, maxZ);
        }

        String type = str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);

        // Cylinder: radius-based
        if (type.contains("CYLINDER")) {
            int r = componentRadius(comp);
            if (r <= 0) r = i(comp.get("radius"), 6);
            int minX = ox - r - margin;
            int maxX = ox + r + margin;
            int minZ = oz - r - margin;
            int maxZ = oz + r + margin;
            return new Rect2(minX, minZ, maxX, maxZ);
        }

        // Extruded polygon: points bbox if present
        if (type.contains("EXTRUDE")) {
            int[] bb = polygonBounds(comp);
            if (bb != null) {
                return new Rect2(ox + bb[0] - margin, oz + bb[2] - margin, ox + bb[1] + margin, oz + bb[3] + margin);
            }
        }

        // Roof: include overhang if present
        if (type.contains("ROOF")) {
            int w = componentWidth(comp);
            int d = componentDepth(comp);
            int overhang = i(comp.get("overhang"), 0);
            if (w > 0 && d > 0) {
                int hx = w / 2 + overhang;
                int hz = d / 2 + overhang;
                return new Rect2(ox - hx - margin, oz - hz - margin, ox + hx + margin, oz + hz + margin);
            }
        }

        // Box-like: w/d centered
        int w = componentWidth(comp);
        int d = componentDepth(comp);
        if (w > 0 && d > 0) {
            int hx = w / 2;
            int hz = d / 2;
            return new Rect2(ox - hx - margin, oz - hz - margin, ox + hx + margin, oz + hz + margin);
        }
        return null;
    }

    /**
     * Returns a list of points to append AFTER start (a), ending with b.
     * If no detour needed, returns [b].
     */
    private static List<int[]> computeDetour(int[] a,
                                             int[] b,
                                             List<Rect2> avoids,
                                             boolean useAStar,
                                             int routingPad,
                                             long routingMaxArea,
                                             int routingMaxNodes,
                                             int preferStraight,
                                             String preferAxis,
                                             int preferAxisWeight,
                                             int leadOut,
                                             int leadIn,
                                             String fromPort,
                                             String toPort,
                                             int leadOutWeight,
                                             int leadInWeight,
                                             String leadRing,
                                             int leadInStepsMaxNodes) {
        // If segment doesn't intersect any avoid, keep it.
        boolean hit = false;
        for (Rect2 r : avoids) {
            if (segmentIntersectsRect(a[0], a[2], b[0], b[2], r)) { hit = true; break; }
        }
        if (!hit) return List.of(b);

        if (useAStar) {
            List<int[]> path = routeAStar2D(a, b, avoids, routingPad, routingMaxArea, routingMaxNodes, preferStraight, preferAxis, preferAxisWeight,
                    leadOut, leadIn, fromPort, toPort, leadOutWeight, leadInWeight, leadRing, leadInStepsMaxNodes);
            if (path != null && !path.isEmpty()) return path;
        }

        // Try detouring around each rect; pick best candidate that avoids all rects.
        List<int[]> best = null;
        long bestCost = Long.MAX_VALUE;

        for (Rect2 r : avoids) {
            List<List<int[]>> candidates = detourCandidates(a, b, r);
            for (List<int[]> cand : candidates) {
                // validate each segment in candidate chain against all avoids
                int[] prev = a;
                boolean ok = true;
                for (int[] p : cand) {
                    for (Rect2 rr : avoids) {
                        if (segmentIntersectsRect(prev[0], prev[2], p[0], p[2], rr)) { ok = false; break; }
                    }
                    if (!ok) break;
                    prev = p;
                }
                if (!ok) continue;

                long cost = 0;
                prev = a;
                for (int[] p : cand) {
                    cost += manhattan2(prev, p);
                    prev = p;
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = cand;
                }
            }
        }

        return best != null ? best : List.of(b);
    }

    /**
     * 2D grid A* routing in XZ, returns a compressed waypoint list (excluding start, including end).
     * Safety caps:
     * - if bounding box too large, return null to fall back to heuristic detours.
     */
    private static List<int[]> routeAStar2D(int[] a,
                                            int[] b,
                                            List<Rect2> avoids,
                                            int pad,
                                            long maxArea,
                                            int maxNodesOverride,
                                            int turnPenalty,
                                            String preferAxis,
                                            int preferAxisWeight,
                                            int leadOut,
                                            int leadIn,
                                            String fromPort,
                                            String toPort,
                                            int leadOutWeight,
                                            int leadInWeight,
                                            String leadRing,
                                            int leadInStepsMaxNodes) {
        // Bounds: endpoints + avoid rects + padding
        int minX = Math.min(a[0], b[0]);
        int maxX = Math.max(a[0], b[0]);
        int minZ = Math.min(a[2], b[2]);
        int maxZ = Math.max(a[2], b[2]);
        for (Rect2 r : avoids) {
            minX = Math.min(minX, r.xMin);
            maxX = Math.max(maxX, r.xMax);
            minZ = Math.min(minZ, r.zMin);
            maxZ = Math.max(maxZ, r.zMax);
        }
        minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;

        int w = maxX - minX + 1;
        int h = maxZ - minZ + 1;
        long area = (long) w * (long) h;
        // hard cap to avoid runaway compile time
        if (area > maxArea) return null;

        int y = (a[1] != 0) ? a[1] : b[1];
        int[] outDir = (leadOut > 0) ? dirFromPort(fromPort) : null; // [dx,dz] outward
        int[] inDir = (leadIn > 0) ? dirFromPort(toPort) : null;     // outward; approach wants opposite
        if (leadOutWeight < 0) leadOutWeight = 0;
        if (leadInWeight < 0) leadInWeight = 0;

        long start = pack(a[0], a[2]);
        long goal = pack(b[0], b[2]);
        if (isBlocked(a[0], a[2], avoids) || isBlocked(b[0], b[2], avoids)) return null;

        java.util.HashMap<Long, Long> came = new java.util.HashMap<>();
        java.util.HashMap<Long, Integer> gScore = new java.util.HashMap<>();
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>();

        gScore.put(start, 0);
        open.add(new Node(start, 0, heuristic(a[0], a[2], b[0], b[2])));

        int maxNodes = maxNodesOverride > 0
                ? maxNodesOverride
                : (int) Math.min(80000L, Math.max(12000L, area)); // scale with area but capped
        int visited = 0;

        boolean useStepRing = !"MANHATTAN".equalsIgnoreCase(leadRing);
        int[] distToGoalSteps = null;
        if (useStepRing && leadIn > 0 && inDir != null) {
            // Reverse BFS from goal to get true remaining steps to goal within this bounded grid.
            // This avoids Manhattan under-estimating distances when detours are required.
            if (leadInStepsMaxNodes < 0) leadInStepsMaxNodes = 0;
            int bfsMaxNodes = (leadInStepsMaxNodes > 0) ? Math.min(maxNodes, Math.max(2000, leadInStepsMaxNodes)) : maxNodes;
            distToGoalSteps = computeDistToGoalSteps(minX, minZ, w, h, b[0], b[2], avoids, bfsMaxNodes);
        }

        while (!open.isEmpty() && visited < maxNodes) {
            Node cur = open.poll();
            long key = cur.key;
            if (key == goal) {
                return reconstructCompressed(came, start, goal, y);
            }
            visited++;

            int cx = unpackX(key);
            int cz = unpackZ(key);
            int baseG = gScore.getOrDefault(key, Integer.MAX_VALUE / 4);

            // 4-neighbors
            for (int[] d : DIR4) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                if (isBlocked(nx, nz, avoids)) continue;
                long nk = pack(nx, nz);
                int penalty = 0;
                if (turnPenalty > 0) {
                    Long parentKey = came.get(key);
                    if (parentKey != null) {
                        int px = unpackX(parentKey);
                        int pz = unpackZ(parentKey);
                        int dx0 = Integer.compare(cx - px, 0);
                        int dz0 = Integer.compare(cz - pz, 0);
                        int dx1 = Integer.compare(nx - cx, 0);
                        int dz1 = Integer.compare(nz - cz, 0);
                        boolean isTurn = (dx0 != dx1) || (dz0 != dz1);
                        if (isTurn) penalty = turnPenalty;
                    }
                }

                // Prefer axis: add a small penalty when moving against preferred axis.
                int axisPenalty = 0;
                String ax = (d[0] != 0) ? "X" : "Z";
                String pref = (preferAxis == null || preferAxis.isBlank()) ? "AUTO" : preferAxis;
                if (preferAxisWeight > 0) {
                    if (pref.equals("AUTO")) {
                        int adx = Math.abs(b[0] - a[0]);
                        int adz = Math.abs(b[2] - a[2]);
                        pref = (adx >= adz) ? "X" : "Z";
                    }
                    if (pref.equals("X") || pref.equals("Z")) {
                        if (!ax.equals(pref)) axisPenalty = preferAxisWeight;
                    }
                }
                int tentative = baseG + 1 + penalty;
                tentative += axisPenalty;

                // Soft lead-out: near start, penalize moves not matching outward port direction
                if (leadOut > 0 && outDir != null) {
                    int distFromStart = useStepRing ? baseG : (Math.abs(cx - a[0]) + Math.abs(cz - a[2]));
                    if (distFromStart < leadOut) {
                        if (d[0] != outDir[0] || d[1] != outDir[1]) tentative += leadOutWeight;
                    }
                }
                // Soft lead-in: near goal, penalize moves that don't approach along the opposite of the port outward dir
                if (leadIn > 0 && inDir != null) {
                    int distToGoal;
                    if (useStepRing && distToGoalSteps != null) {
                        int idx = (cx - minX) + (cz - minZ) * w;
                        if (idx >= 0 && idx < distToGoalSteps.length && distToGoalSteps[idx] >= 0) distToGoal = distToGoalSteps[idx];
                        else distToGoal = Math.abs(cx - b[0]) + Math.abs(cz - b[2]);
                    } else {
                        distToGoal = Math.abs(cx - b[0]) + Math.abs(cz - b[2]);
                    }
                    if (distToGoal < leadIn) {
                        int adx = -inDir[0];
                        int adz = -inDir[1];
                        if (d[0] != adx || d[1] != adz) tentative += leadInWeight;
                    }
                }
                int best = gScore.getOrDefault(nk, Integer.MAX_VALUE / 4);
                if (tentative < best) {
                    came.put(nk, key);
                    gScore.put(nk, tentative);
                    int f = tentative + heuristic(nx, nz, b[0], b[2]);
                    open.add(new Node(nk, tentative, f));
                }
            }
        }
        return null;
    }

    private static int[] computeDistToGoalSteps(int minX, int minZ, int w, int h, int goalX, int goalZ, List<Rect2> avoids, int maxNodes) {
        int[] dist = new int[w * h];
        java.util.Arrays.fill(dist, -1);
        if (goalX < minX || goalX > (minX + w - 1) || goalZ < minZ || goalZ > (minZ + h - 1)) return dist;
        if (isBlocked(goalX, goalZ, avoids)) return dist;

        int gIdx = (goalX - minX) + (goalZ - minZ) * w;
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        dist[gIdx] = 0;
        q.add(gIdx);
        int visited = 0;
        while (!q.isEmpty() && visited < maxNodes) {
            int idx = q.poll();
            int cx = (idx % w) + minX;
            int cz = (idx / w) + minZ;
            int cd = dist[idx];
            visited++;
            for (int[] d : DIR4) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < minX || nx > (minX + w - 1) || nz < minZ || nz > (minZ + h - 1)) continue;
                if (isBlocked(nx, nz, avoids)) continue;
                int nIdx = (nx - minX) + (nz - minZ) * w;
                if (dist[nIdx] != -1) continue;
                dist[nIdx] = cd + 1;
                q.add(nIdx);
            }
        }
        return dist;
    }

    private static String axisFromPort(String port) {
        if (port == null) return null;
        String p = normalizePortKey(port);
        // allow prefixed directional ports like start_east / end_north / entrance_south
        String base = p;
        int idx = p.lastIndexOf('_');
        if (idx >= 0 && idx + 1 < p.length()) base = p.substring(idx + 1);
        // directional ports imply axis
        if (base.equals("east") || base.equals("west") || base.equals("left") || base.equals("right")) return "X";
        if (base.equals("north") || base.equals("south") || base.equals("front") || base.equals("back") || base.equals("entrance") || base.equals("exit")) return "Z";
        // corners imply both; keep AUTO
        return null;
    }

    private record Node(long key, int g, int f) implements Comparable<Node> {
        @Override public int compareTo(Node o) { return Integer.compare(this.f, o.f); }
    }

    private static int heuristic(int x0, int z0, int x1, int z1) {
        return Math.abs(x0 - x1) + Math.abs(z0 - z1);
    }

    private static boolean isBlocked(int x, int z, List<Rect2> avoids) {
        for (Rect2 r : avoids) {
            if (x >= r.xMin && x <= r.xMax && z >= r.zMin && z <= r.zMax) return true;
        }
        return false;
    }

    private static List<int[]> reconstructCompressed(java.util.HashMap<Long, Long> came, long start, long goal, int y) {
        java.util.ArrayList<long[]> rev = new java.util.ArrayList<>();
        long cur = goal;
        rev.add(new long[]{unpackX(cur), unpackZ(cur)});
        while (cur != start) {
            Long p = came.get(cur);
            if (p == null) return null;
            cur = p;
            rev.add(new long[]{unpackX(cur), unpackZ(cur)});
        }
        // reverse
        java.util.Collections.reverse(rev);
        // compress into turn points; skip the first point (start), include end
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int lastDx = 0, lastDz = 0;
        for (int i = 1; i < rev.size(); i++) {
            int x0 = (int) rev.get(i - 1)[0], z0 = (int) rev.get(i - 1)[1];
            int x1 = (int) rev.get(i)[0], z1 = (int) rev.get(i)[1];
            int dx = Integer.compare(x1 - x0, 0);
            int dz = Integer.compare(z1 - z0, 0);
            boolean turn = (i == 1) || (dx != lastDx) || (dz != lastDz);
            if (turn && i - 1 > 0) {
                // add the previous point as a waypoint
                out.add(new int[]{x0, y, z0});
            }
            lastDx = dx; lastDz = dz;
        }
        long[] end = rev.getLast();
        out.add(new int[]{(int) end[0], y, (int) end[1]});
        return out;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long k) { return (int) (k >> 32); }
    private static int unpackZ(long k) { return (int) k; }

    private static List<List<int[]>> detourCandidates(int[] a, int[] b, Rect2 r) {
        int yA = a[1];
        int yB = b[1];
        int y = (yA != 0) ? yA : yB;

        // Corners around expanded rect (one block outside)
        int lx = r.xMin - 1, rx = r.xMax + 1;
        int nz = r.zMin - 1, sz = r.zMax + 1;

        // Candidate 1: go around left side (x=lx), then to b
        List<int[]> c1 = List.of(new int[]{lx, y, a[2]}, new int[]{lx, y, b[2]}, b);
        // Candidate 2: right side
        List<int[]> c2 = List.of(new int[]{rx, y, a[2]}, new int[]{rx, y, b[2]}, b);
        // Candidate 3: north side
        List<int[]> c3 = List.of(new int[]{a[0], y, nz}, new int[]{b[0], y, nz}, b);
        // Candidate 4: south side
        List<int[]> c4 = List.of(new int[]{a[0], y, sz}, new int[]{b[0], y, sz}, b);
        // Candidate 5-8: corner pivots (diagonal-ish L turns)
        List<int[]> c5 = List.of(new int[]{lx, y, nz}, b);
        List<int[]> c6 = List.of(new int[]{lx, y, sz}, b);
        List<int[]> c7 = List.of(new int[]{rx, y, nz}, b);
        List<int[]> c8 = List.of(new int[]{rx, y, sz}, b);

        return List.of(c1, c2, c3, c4, c5, c6, c7, c8);
    }

    private static long manhattan2(int[] a, int[] b) {
        return (long) Math.abs(a[0] - b[0]) + (long) Math.abs(a[2] - b[2]);
    }

    // Segment-rect intersection in XZ (axis-aligned rect)
    private static boolean segmentIntersectsRect(int x0, int z0, int x1, int z1, Rect2 r) {
        // Quick reject: both endpoints on one side
        if (x0 < r.xMin && x1 < r.xMin) return false;
        if (x0 > r.xMax && x1 > r.xMax) return false;
        if (z0 < r.zMin && z1 < r.zMin) return false;
        if (z0 > r.zMax && z1 > r.zMax) return false;

        // If either endpoint inside rect -> intersects
        if (x0 >= r.xMin && x0 <= r.xMax && z0 >= r.zMin && z0 <= r.zMax) return true;
        if (x1 >= r.xMin && x1 <= r.xMax && z1 >= r.zMin && z1 <= r.zMax) return true;

        // Check intersection with rectangle edges.
        int rx0 = r.xMin, rz0 = r.zMin, rx1 = r.xMax, rz1 = r.zMax;
        return segmentsIntersect(x0, z0, x1, z1, rx0, rz0, rx1, rz0) // north edge
                || segmentsIntersect(x0, z0, x1, z1, rx1, rz0, rx1, rz1) // east edge
                || segmentsIntersect(x0, z0, x1, z1, rx1, rz1, rx0, rz1) // south edge
                || segmentsIntersect(x0, z0, x1, z1, rx0, rz1, rx0, rz0); // west edge
    }

    private static boolean segmentsIntersect(int ax, int az, int bx, int bz, int cx, int cz, int dx, int dz) {
        int o1 = orient(ax, az, bx, bz, cx, cz);
        int o2 = orient(ax, az, bx, bz, dx, dz);
        int o3 = orient(cx, cz, dx, dz, ax, az);
        int o4 = orient(cx, cz, dx, dz, bx, bz);

        if (o1 != o2 && o3 != o4) return true;
        // colinear cases
        if (o1 == 0 && onSeg(ax, az, bx, bz, cx, cz)) return true;
        if (o2 == 0 && onSeg(ax, az, bx, bz, dx, dz)) return true;
        if (o3 == 0 && onSeg(cx, cz, dx, dz, ax, az)) return true;
        return o4 == 0 && onSeg(cx, cz, dx, dz, bx, bz);
    }

    // orientation of (a->b) x (a->c); returns -1,0,1
    private static int orient(int ax, int az, int bx, int bz, int cx, int cz) {
        long v = (long) (bx - ax) * (cz - az) - (long) (bz - az) * (cx - ax);
        if (v == 0) return 0;
        return v > 0 ? 1 : -1;
    }

    private static boolean onSeg(int ax, int az, int bx, int bz, int px, int pz) {
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx) && pz >= Math.min(az, bz) && pz <= Math.max(az, bz);
    }

    private static int[] resolveWaypoint(Object v,
                                         Map<String, Map<String, Object>> byId,
                                         Map<String, int[]> originById,
                                         Map<String, Map<String, int[]>> portsById) {
        if (v == null) return null;
        // direct endpoint reference
        Endpoint e = parseEndpoint(v);
        if (e != null) return resolveEndpoint(e, byId, originById, portsById);

        // raw coordinate map: {x,y,z}
        if (v instanceof Map<?, ?> m) {
            // if it has no component/id field, treat as local absolute point
            Object cid = m.get("component");
            if (cid == null) cid = m.get("id");
            if (cid == null) {
                int x = i(m.get("x"), 0);
                int y = i(m.get("y"), 0);
                int z = i(m.get("z"), 0);
                return new int[]{x, y, z};
            }
        }
        return null;
    }

    private static int[] computeLeadPoint(int[] p, String port, int dist, List<Rect2> avoids) {
        if (p == null || dist <= 0) return null;
        int[] d = dirFromPort(port);
        if (d == null) return null;
        int y = p[1];
        // shrink if target falls inside avoid
        for (int k = dist; k >= 1; k--) {
            int x = p[0] + d[0] * k;
            int z = p[2] + d[1] * k;
            if (!isBlocked(x, z, avoids)) {
                return new int[]{x, y, z};
            }
        }
        return null;
    }

    // Returns [dx, dz] in local XZ for a port name. Diagonals return null (no lead).
    private static int[] dirFromPort(String port) {
        if (port == null) return null;
        String p = normalizePortKey(port);
        // allow prefixed directional ports like start_east / end_north / entrance_south
        String base = p;
        int idx = p.lastIndexOf('_');
        if (idx >= 0 && idx + 1 < p.length()) base = p.substring(idx + 1);
        return switch (base) {
            case "north", "back", "exit" -> new int[]{0, -1};
            case "south", "front", "entrance", "gate", "in" -> new int[]{0, 1};
            case "east", "right" -> new int[]{1, 0};
            case "west", "left" -> new int[]{-1, 0};
            default -> null;
        };
    }

    private static int[] resolveEndpoint(Endpoint e,
                                         Map<String, Map<String, Object>> byId,
                                         Map<String, int[]> originById,
                                         Map<String, Map<String, int[]>> portsById) {
        if (e == null) return null;
        int[] base = originById.get(e.componentId);
        Map<String, Object> comp = byId.get(e.componentId);
        if (base == null || comp == null) return null;

        int ox = base[0], oy = base[1], oz = base[2];
        int ax = 0, ay = 0, az = 0;

        // Port offset wins (Topology-friendly)
        if (e.port != null && portsById != null) {
            Map<String, int[]> ports = portsById.get(e.componentId);
            if (ports != null) {
                String key = normalizePortKey(e.port);
                int[] p = ports.get(key);
                if (p != null) {
                    ax += p[0];
                    ay += p[1];
                    az += p[2];
                }
            }
        }

        // anchor offsets (relative to component origin)
        String a = (e.anchor == null ? "CENTER" : e.anchor);
        if (a.equals("TOP_CENTER")) {
            ay = componentHeight(comp);
        }  // no-op


        return new int[]{ox + ax + e.dx, oy + ay + e.dy, oz + az + e.dz};
    }

    private static int componentHeight(Map<String, Object> comp) {
        if (comp == null) return 0;
        String type = str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);
        if (type.contains("ROOF")) {
            // ROOF_COVER: treat rise as its vertical extent for port inference
            int rise = i(comp.get("rise"), i(comp.get("height"), 0));
            int baseY = i(comp.get("y"), 0);
            if (rise > 0) return baseY + rise;
            return baseY;
        }
        // common fields
        int h = i(comp.get("h"), i(comp.get("height"), 0));
        return Math.max(h, 0);
        // cylinder uses height too
    }

    private static int componentWidth(Map<String, Object> comp) {
        if (comp == null) return 0;
        return i(comp.get("w"), i(comp.get("width"), 0));
    }

    private static int componentDepth(Map<String, Object> comp) {
        if (comp == null) return 0;
        return i(comp.get("d"), i(comp.get("depth"), 0));
    }

    private static int componentRadius(Map<String, Object> comp) {
        if (comp == null) return 0;
        return i(comp.get("r"), i(comp.get("radius"), 0));
    }

    private static int[] componentOrigin(Map<String, Object> comp) {
        int dx = 0, dy = 0, dz = 0;
        Object at = comp != null ? comp.get("at") : null;
        if (at instanceof Map<?, ?> am) {
            dx = i(am.get("x"), 0);
            dy = i(am.get("y"), 0);
            dz = i(am.get("z"), 0);
        } else if (comp != null) {
            dx = i(comp.get("x"), 0);
            dy = i(comp.get("y"), 0);
            dz = i(comp.get("z"), 0);
        }
        return new int[]{dx, dy, dz};
    }

    private static Map<String, int[]> buildPorts(Map<String, Object> comp) {
        Map<String, int[]> ports = new HashMap<>();
        // built-ins
        ports.put("center", new int[]{0, 0, 0});
        ports.put("bottom_center", new int[]{0, 0, 0});
        ports.put("top_center", new int[]{0, componentHeight(comp), 0});
        // common aliases
        ports.put("bottom", ports.get("bottom_center"));
        ports.put("top", ports.get("top_center"));
        ports.put("mid", ports.get("center"));

        String type = str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);

        // SPLINE components: derive start/end ports from points
        if (type.contains("SPLINE")) {
            Object ptsObj = comp.get("points");
            if (ptsObj instanceof List<?> list && list.size() >= 2) {
                int[] a = readPoint(list.getFirst());
                int[] b = readPoint(list.getLast());
                if (a != null) {
                    ports.put("start", a);
                    ports.put("entrance", a);
                    ports.put("in", a);
                }
                if (b != null) {
                    ports.put("end", b);
                    ports.put("exit", b);
                    ports.put("out", b);
                }

                // Tangent-direction ports (connectable): start_{n/e/s/w}, end_{n/e/s/w}
                // These keep the same endpoint position, but encode "outward" XZ direction in the port name,
                // so routing (lead-out/lead-in / preferDoorAxis) can infer a sensible axis/orientation.
                int[] a1 = readPoint(list.get(1));
                if (a != null && a1 != null) {
                    int dx = a1[0] - a[0];
                    int dz = a1[2] - a[2];
                    String dir = null;
                    if (dx != 0 || dz != 0) {
                        if (Math.abs(dx) >= Math.abs(dz)) dir = (dx >= 0) ? "east" : "west";
                        else dir = (dz >= 0) ? "south" : "north";
                    }
                    if (dir != null) {
                        ports.put("start_" + dir, a);
                    }
                }
                int[] b0 = readPoint(list.get(list.size() - 2));
                if (b != null && b0 != null) {
                    int dx = b[0] - b0[0];
                    int dz = b[2] - b0[2];
                    String dir = null;
                    if (dx != 0 || dz != 0) {
                        if (Math.abs(dx) >= Math.abs(dz)) dir = (dx >= 0) ? "east" : "west";
                        else dir = (dz >= 0) ? "south" : "north";
                    }
                    if (dir != null) {
                        ports.put("end_" + dir, b);
                    }
                }

                if (a != null && b != null) {
                    ports.put("center", new int[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2});
                }
            }
        }

        if (type.contains("CYLINDER")) {
            int r = componentRadius(comp);
            ports.put("north", new int[]{0, 0, -r});
            ports.put("south", new int[]{0, 0, r});
            ports.put("east", new int[]{r, 0, 0});
            ports.put("west", new int[]{-r, 0, 0});

            // 8-way (approx): diagonal ports on the circle
            int r45 = (int) Math.round(r / Math.sqrt(2.0));
            ports.put("ne", new int[]{r45, 0, -r45});
            ports.put("nw", new int[]{-r45, 0, -r45});
            ports.put("se", new int[]{r45, 0, r45});
            ports.put("sw", new int[]{-r45, 0, r45});

            // semantic corners for consistency
            ports.put("front_left", ports.get("sw"));
            ports.put("front_right", ports.get("se"));
            ports.put("back_left", ports.get("nw"));
            ports.put("back_right", ports.get("ne"));
            ports.put("corner_front_left", ports.get("front_left"));
            ports.put("corner_front_right", ports.get("front_right"));
            ports.put("corner_back_left", ports.get("back_left"));
            ports.put("corner_back_right", ports.get("back_right"));
        } else {
            int w = componentWidth(comp);
            int d = componentDepth(comp);
            if (w > 0 || d > 0) {
                int hx = w / 2;
                int hz = d / 2;
                ports.put("north", new int[]{0, 0, -hz});
                ports.put("south", new int[]{0, 0, hz});
                ports.put("east", new int[]{hx, 0, 0});
                ports.put("west", new int[]{-hx, 0, 0});

                // corners / edge midpoints (useful for multiple corridors/roads)
                ports.put("nw", new int[]{-hx, 0, -hz});
                ports.put("ne", new int[]{hx, 0, -hz});
                ports.put("sw", new int[]{-hx, 0, hz});
                ports.put("se", new int[]{hx, 0, hz});

                ports.put("front_left", new int[]{-hx, 0, hz});
                ports.put("front_right", new int[]{hx, 0, hz});
                ports.put("back_left", new int[]{-hx, 0, -hz});
                ports.put("back_right", new int[]{hx, 0, -hz});

                ports.put("corner_front_left", ports.get("front_left"));
                ports.put("corner_front_right", ports.get("front_right"));
                ports.put("corner_back_left", ports.get("back_left"));
                ports.put("corner_back_right", ports.get("back_right"));
            }
        }

        // EXTRUDE_POLYGON(points): if points provided, infer bbox-based ports (overrides rect defaults).
        if (type.contains("EXTRUDE")) {
            int[] bb = polygonBounds(comp);
            if (bb != null) {
                int xMin = bb[0], xMax = bb[1], zMin = bb[2], zMax = bb[3];
                int midX = (xMin + xMax) / 2;
                int midZ = (zMin + zMax) / 2;
                ports.put("north", new int[]{midX, 0, zMin});
                ports.put("south", new int[]{midX, 0, zMax});
                ports.put("east", new int[]{xMax, 0, midZ});
                ports.put("west", new int[]{xMin, 0, midZ});
                ports.put("nw", new int[]{xMin, 0, zMin});
                ports.put("ne", new int[]{xMax, 0, zMin});
                ports.put("sw", new int[]{xMin, 0, zMax});
                ports.put("se", new int[]{xMax, 0, zMax});
                ports.put("front_left", ports.get("sw"));
                ports.put("front_right", ports.get("se"));
                ports.put("back_left", ports.get("nw"));
                ports.put("back_right", ports.get("ne"));
                ports.put("corner_front_left", ports.get("front_left"));
                ports.put("corner_front_right", ports.get("front_right"));
                ports.put("corner_back_left", ports.get("back_left"));
                ports.put("corner_back_right", ports.get("back_right"));
            }
        }

        // ROOF_COVER(GABLE): add ridge ports so topology can connect to the roof spine.
        // Note: component "type" is reserved for the component kind (ROOF_COVER / ROOF). Roof shape kind is read
        // from roofType/coverType/cover/roof_cover_type.
        if (type.contains("ROOF")) {
            String roofCoverType = str(
                    comp.get("roofType"),
                    str(comp.get("coverType"),
                            str(comp.get("cover"),
                                    str(comp.get("roof_cover_type"),
                                            str(comp.get("kind"), "GABLE")
                                    )
                            )
                    )
            ).trim().toUpperCase(Locale.ROOT);

            if (roofCoverType.contains("GABLE")) {
                int w = componentWidth(comp);
                int d = componentDepth(comp);
                int overhang = i(comp.get("overhang"), 0);
                int baseY = i(comp.get("y"), 0);
                int topY = componentHeight(comp);

                if (w > 0 && d > 0) {
                    int hx = w / 2 + overhang;
                    int hz = d / 2 + overhang;
                    boolean ridgeAlongX = w >= d;
                    ports.put("ridge_center", new int[]{0, topY, 0});
                    if (ridgeAlongX) {
                        // ridge from west to east at z=0
                        ports.put("ridge_west", new int[]{-hx, topY, 0});
                        ports.put("ridge_east", new int[]{hx, topY, 0});
                        alias(ports, "ridge_left", "ridge_west");
                        alias(ports, "ridge_right", "ridge_east");
                    } else {
                        // ridge from north to south at x=0
                        ports.put("ridge_north", new int[]{0, topY, -hz});
                        ports.put("ridge_south", new int[]{0, topY, hz});
                        alias(ports, "ridge_back", "ridge_north");
                        alias(ports, "ridge_front", "ridge_south");
                    }
                    // base plane reference (useful for attaching stairs/entrance canopies)
                    ports.put("roof_base_center", new int[]{0, baseY, 0});
                }
            }
        }

        // semantic direction aliases in our local coordinate convention:
        // local +Z is FRONT (default entrance), local -Z is BACK.
        // local +X is RIGHT, local -X is LEFT.
        alias(ports, "front", "south");
        alias(ports, "back", "north");
        alias(ports, "right", "east");
        alias(ports, "left", "west");
        alias(ports, "entrance", "front");
        alias(ports, "exit", "back");
        alias(ports, "in", "entrance");
        alias(ports, "out", "exit");
        alias(ports, "gate", "entrance");
        alias(ports, "corner_nw", "nw");
        alias(ports, "corner_ne", "ne");
        alias(ports, "corner_sw", "sw");
        alias(ports, "corner_se", "se");

        // user-defined ports override built-ins
        Object pObj = comp.get("ports");
        if (pObj instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (e.getKey() == null) continue;
                String name = String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
                Object pv = e.getValue();
                if (pv instanceof Map<?, ?> m) {
                    int x = i(m.get("x"), 0);
                    int y = i(m.get("y"), 0);
                    int z = i(m.get("z"), 0);
                    ports.put(normalizePortKey(name), new int[]{x, y, z});
                }
            }
        }
        return ports;
    }

    private static int[] readPoint(Object v) {
        if (!(v instanceof Map<?, ?> pm)) return null;
        int x = i(pm.get("x"), 0);
        int y = i(pm.get("y"), 0);
        int z = i(pm.get("z"), 0);
        return new int[]{x, y, z};
    }

    private static int[] polygonBounds(Map<String, Object> comp) {
        if (comp == null) return null;
        Object ptsObj = comp.get("points");
        if (!(ptsObj instanceof List<?> pts)) return null;
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        boolean any = false;
        for (Object p : pts) {
            if (p instanceof Map<?, ?> pm) {
                int x = i(pm.get("x"), 0);
                int z = i(pm.get("z"), 0);
                xMin = Math.min(xMin, x);
                xMax = Math.max(xMax, x);
                zMin = Math.min(zMin, z);
                zMax = Math.max(zMax, z);
                any = true;
            }
        }
        if (!any) return null;
        return new int[]{xMin, xMax, zMin, zMax};
    }

    private static void alias(Map<String, int[]> ports, String alias, String target) {
        if (ports == null) return;
        int[] t = ports.get(normalizePortKey(target));
        if (t != null) ports.put(normalizePortKey(alias), t);
    }

    private static String normalizePortKey(String s) {
        if (s == null) return "center";
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return "center";
        // accept dash/space forms
        k = k.replace('-', '_').replace(' ', '_');
        // a few extra synonyms
        if (k.equals("n")) k = "north";
        if (k.equals("s")) k = "south";
        if (k.equals("e")) k = "east";
        if (k.equals("w")) k = "west";
        if (k.equals("topcenter")) k = "top_center";
        if (k.equals("bottomcenter")) k = "bottom_center";
        if (k.equals("frontleft")) k = "front_left";
        if (k.equals("frontright")) k = "front_right";
        if (k.equals("backleft")) k = "back_left";
        if (k.equals("backright")) k = "back_right";
        return k;
    }

    private static Map<String, Object> op(String name, Object... kv) {
        Map<String, Object> o = new HashMap<>();
        o.put("op", name);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            o.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return o;
    }

    private static void copy(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key) && src.get(key) != null) dst.put(key, src.get(key));
    }

    private static void copyInt(Map<String, Object> src, Map<String, Object> dst, String key, int def) {
        dst.put(key, i(src.get(key), def));
    }

    private static int i(Object v, int def) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return def;
    }

    private static double d(Object v) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return Double.NaN;
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }
}


