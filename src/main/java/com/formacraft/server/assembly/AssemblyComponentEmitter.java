package com.formacraft.server.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expands topology components into executable assembly ops.
 */
final class AssemblyComponentEmitter {
    private AssemblyComponentEmitter() {}

    static void emitComponent(List<Map<String, Object>> ops, Map<String, Object> comp) {
        String type = AssemblyCompilerUtils.str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = AssemblyCompilerUtils.str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT); // allow op-like components
        if (type.isBlank()) return;

        // placement (local offsets)
        int dx, dy, dz;
        Object at = comp.get("at");
        if (at instanceof Map<?, ?> am) {
            dx = AssemblyCompilerUtils.i(am.get("x"), 0);
            dy = AssemblyCompilerUtils.i(am.get("y"), 0);
            dz = AssemblyCompilerUtils.i(am.get("z"), 0);
        } else {
            dx = AssemblyCompilerUtils.i(comp.get("x"), 0);
            dy = AssemblyCompilerUtils.i(comp.get("y"), 0);
            dz = AssemblyCompilerUtils.i(comp.get("z"), 0);
        }

        ops.add(AssemblyCompilerUtils.op("PUSH_ORIGIN", "dx", dx, "dy", dy, "dz", dz));

        // v1 component macros
        switch (type) {
            case "ANCHOR_FOOTPRINT", "FOOTPRINT_ANCHOR" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ANCHOR_FOOTPRINT");
                AssemblyCompilerUtils.copyInt(comp, o, "x0", AssemblyCompilerUtils.i(comp.get("x0"), 0));
                AssemblyCompilerUtils.copyInt(comp, o, "x1", AssemblyCompilerUtils.i(comp.get("x1"), 0));
                AssemblyCompilerUtils.copyInt(comp, o, "z0", AssemblyCompilerUtils.i(comp.get("z0"), 0));
                AssemblyCompilerUtils.copyInt(comp, o, "z1", AssemblyCompilerUtils.i(comp.get("z1"), 0));
                AssemblyCompilerUtils.copyInt(comp, o, "yBase", AssemblyCompilerUtils.i(comp.get("yBase"), AssemblyCompilerUtils.i(comp.get("y"), 0)));
                AssemblyCompilerUtils.copyInt(comp, o, "maxDepth", AssemblyCompilerUtils.i(comp.get("maxDepth"), AssemblyCompilerUtils.i(comp.get("anchorDepth"), 32)));
                AssemblyCompilerUtils.copy(comp, o, "material");
                AssemblyCompilerUtils.copy(comp, o, "stopOnSolid");
                AssemblyCompilerUtils.copy(comp, o, "allowWaterEdit");
                AssemblyCompilerUtils.copy(comp, o, "allowLavaEdit");
                ops.add(o);
            }
            case "ANCHORAGE", "ANCHORAGE_BLOCK" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ANCHORAGE");
                AssemblyCompilerUtils.copyInt(comp, o, "w", AssemblyCompilerUtils.i(comp.get("w"), AssemblyCompilerUtils.i(comp.get("width"), 12)));
                AssemblyCompilerUtils.copyInt(comp, o, "d", AssemblyCompilerUtils.i(comp.get("d"), AssemblyCompilerUtils.i(comp.get("depth"), 10)));
                AssemblyCompilerUtils.copyInt(comp, o, "h", AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), 8)));
                AssemblyCompilerUtils.copyInt(comp, o, "yBase", AssemblyCompilerUtils.i(comp.get("yBase"), AssemblyCompilerUtils.i(comp.get("y"), 0)));
                AssemblyCompilerUtils.copyInt(comp, o, "maxDepth", AssemblyCompilerUtils.i(comp.get("maxDepth"), AssemblyCompilerUtils.i(comp.get("anchorDepth"), 24)));
                AssemblyCompilerUtils.copy(comp, o, "solid");
                AssemblyCompilerUtils.copy(comp, o, "material");
                AssemblyCompilerUtils.copy(comp, o, "carve");
                AssemblyCompilerUtils.copy(comp, o, "allowWaterEdit");
                AssemblyCompilerUtils.copy(comp, o, "allowLavaEdit");
                // detailing
                AssemblyCompilerUtils.copyInt(comp, o, "topBevel", AssemblyCompilerUtils.i(comp.get("topBevel"), AssemblyCompilerUtils.i(comp.get("bevel"), 0)));
                AssemblyCompilerUtils.copy(comp, o, "holes");
                AssemblyCompilerUtils.copy(comp, o, "cableHoles");
                AssemblyCompilerUtils.copyInt(comp, o, "guardWallHeight", AssemblyCompilerUtils.i(comp.get("guardWallHeight"), AssemblyCompilerUtils.i(comp.get("parapetHeight"), 0)));
                AssemblyCompilerUtils.copyInt(comp, o, "guardWallInset", AssemblyCompilerUtils.i(comp.get("guardWallInset"), 0));
                AssemblyCompilerUtils.copy(comp, o, "guardWallCrenels");
                AssemblyCompilerUtils.copy(comp, o, "crenels");
                AssemblyCompilerUtils.copy(comp, o, "guardWallMaterial");
                AssemblyCompilerUtils.copy(comp, o, "guardWall");
                ops.add(o);
            }
            case "TENSION_CABLE", "CABLE", "SAG_CABLE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "TENSION_CABLE");
                AssemblyCompilerUtils.copy(comp, o, "from");
                AssemblyCompilerUtils.copy(comp, o, "to");
                AssemblyCompilerUtils.copyInt(comp, o, "sag", AssemblyCompilerUtils.i(comp.get("sag"), AssemblyCompilerUtils.i(comp.get("droop"), -1)));
                AssemblyCompilerUtils.copyInt(comp, o, "samples", AssemblyCompilerUtils.i(comp.get("samples"), AssemblyCompilerUtils.i(comp.get("steps"), -1)));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "material");
                AssemblyCompilerUtils.copyInt(comp, o, "hangersEvery", AssemblyCompilerUtils.i(comp.get("hangersEvery"), AssemblyCompilerUtils.i(comp.get("hangerEvery"), 0)));
                AssemblyCompilerUtils.copyInt(comp, o, "hangersToY", AssemblyCompilerUtils.i(comp.get("hangersToY"), AssemblyCompilerUtils.i(comp.get("hangerToY"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copy(comp, o, "hangersMaterial");
                AssemblyCompilerUtils.copyInt(comp, o, "cableCount", AssemblyCompilerUtils.i(comp.get("cableCount"), AssemblyCompilerUtils.i(comp.get("count"), 1)));
                AssemblyCompilerUtils.copyInt(comp, o, "cableSpacing", AssemblyCompilerUtils.i(comp.get("cableSpacing"), AssemblyCompilerUtils.i(comp.get("spacing"), 3)));
                AssemblyCompilerUtils.copy(comp, o, "cableAxis");
                ops.add(o);
            }
            case "FRAME_GRID_3D", "FRAMEGRID_3D", "SPACE_FRAME", "EXOSKELETON" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "FRAME_GRID_3D");
                // Prefer explicit bounds; otherwise derive from w/d/h as centered box.
                int x0 = AssemblyCompilerUtils.i(comp.get("x0"), Integer.MIN_VALUE);
                int x1 = AssemblyCompilerUtils.i(comp.get("x1"), Integer.MIN_VALUE);
                int y0 = AssemblyCompilerUtils.i(comp.get("y0"), Integer.MIN_VALUE);
                int y1 = AssemblyCompilerUtils.i(comp.get("y1"), Integer.MIN_VALUE);
                int z0 = AssemblyCompilerUtils.i(comp.get("z0"), Integer.MIN_VALUE);
                int z1 = AssemblyCompilerUtils.i(comp.get("z1"), Integer.MIN_VALUE);
                if (x0 != Integer.MIN_VALUE && x1 != Integer.MIN_VALUE
                        && y0 != Integer.MIN_VALUE && y1 != Integer.MIN_VALUE
                        && z0 != Integer.MIN_VALUE && z1 != Integer.MIN_VALUE) {
                    o.put("x0", x0); o.put("x1", x1);
                    o.put("y0", y0); o.put("y1", y1);
                    o.put("z0", z0); o.put("z1", z1);
                } else {
                    int w = AssemblyCompilerUtils.i(comp.get("w"), AssemblyCompilerUtils.i(comp.get("width"), 16));
                    int d = AssemblyCompilerUtils.i(comp.get("d"), AssemblyCompilerUtils.i(comp.get("depth"), 16));
                    int h = AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), 24));
                    int hx = Math.max(1, w / 2);
                    int hz = Math.max(1, d / 2);
                    o.put("x0", -hx); o.put("x1", hx);
                    o.put("y0", 0); o.put("y1", Math.max(1, h));
                    o.put("z0", -hz); o.put("z1", hz);
                }
                AssemblyCompilerUtils.copyInt(comp, o, "stepX", AssemblyCompilerUtils.i(comp.get("stepX"), AssemblyCompilerUtils.i(comp.get("sx"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "stepY", AssemblyCompilerUtils.i(comp.get("stepY"), AssemblyCompilerUtils.i(comp.get("sy"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "stepZ", AssemblyCompilerUtils.i(comp.get("stepZ"), AssemblyCompilerUtils.i(comp.get("sz"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "step", AssemblyCompilerUtils.i(comp.get("step"), Integer.MIN_VALUE));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "mode");
                AssemblyCompilerUtils.copy(comp, o, "diagonal");
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "STAIR_SYSTEM", "STAIRS_SYSTEM", "STAIRCASE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "STAIR_SYSTEM");
                AssemblyCompilerUtils.copy(comp, o, "from");
                AssemblyCompilerUtils.copy(comp, o, "to");
                AssemblyCompilerUtils.copyInt(comp, o, "width", AssemblyCompilerUtils.i(comp.get("width"), 2));
                AssemblyCompilerUtils.copyInt(comp, o, "clearHeight", AssemblyCompilerUtils.i(comp.get("clearHeight"), AssemblyCompilerUtils.i(comp.get("clear_h"), 3)));
                AssemblyCompilerUtils.copy(comp, o, "carve");
                AssemblyCompilerUtils.copy(comp, o, "support");
                AssemblyCompilerUtils.copy(comp, o, "stairs");
                AssemblyCompilerUtils.copy(comp, o, "floor");
                AssemblyCompilerUtils.copy(comp, o, "supportMaterial");
                ops.add(o);
            }
            case "BEZIER_SURFACE", "BEZIER_PATCH", "BEZIER" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "BEZIER_SURFACE");
                AssemblyCompilerUtils.copy(comp, o, "points"); // 16 points or 4x4
                AssemblyCompilerUtils.copyInt(comp, o, "uSamples", AssemblyCompilerUtils.i(comp.get("uSamples"), AssemblyCompilerUtils.i(comp.get("u"), 24)));
                AssemblyCompilerUtils.copyInt(comp, o, "vSamples", AssemblyCompilerUtils.i(comp.get("vSamples"), AssemblyCompilerUtils.i(comp.get("v"), 24)));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "connectSamples");
                AssemblyCompilerUtils.copyInt(comp, o, "connectMaxStep", AssemblyCompilerUtils.i(comp.get("connectMaxStep"), 2));
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "BEZIER_SURFACE_SET", "BEZIER_PATCH_SET", "BEZIER_SET" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "BEZIER_SURFACE_SET");
                AssemblyCompilerUtils.copy(comp, o, "patches");
                AssemblyCompilerUtils.copy(comp, o, "topology");
                AssemblyCompilerUtils.copy(comp, o, "grid"); // legacy
                AssemblyCompilerUtils.copyInt(comp, o, "uSamples", AssemblyCompilerUtils.i(comp.get("uSamples"), AssemblyCompilerUtils.i(comp.get("u"), 24)));
                AssemblyCompilerUtils.copyInt(comp, o, "vSamples", AssemblyCompilerUtils.i(comp.get("vSamples"), AssemblyCompilerUtils.i(comp.get("v"), 24)));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "connectSamples");
                AssemblyCompilerUtils.copy(comp, o, "stitch");
                AssemblyCompilerUtils.copyInt(comp, o, "stitchEpsilon", AssemblyCompilerUtils.i(comp.get("stitchEpsilon"), AssemblyCompilerUtils.i(comp.get("stitch_eps"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "stitchSamples", AssemblyCompilerUtils.i(comp.get("stitchSamples"), AssemblyCompilerUtils.i(comp.get("stitch_samples"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copy(comp, o, "stitchResampleMode");
                AssemblyCompilerUtils.copy(comp, o, "stitch_resample_mode");
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "REVOLVE_SURFACE", "REVOLVE", "SURFACE_OF_REVOLUTION" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "REVOLVE_SURFACE");
                AssemblyCompilerUtils.copy(comp, o, "profilePoints");
                AssemblyCompilerUtils.copy(comp, o, "profileRings");
                AssemblyCompilerUtils.copy(comp, o, "rings");
                AssemblyCompilerUtils.copy(comp, o, "points");
                AssemblyCompilerUtils.copyInt(comp, o, "segments", AssemblyCompilerUtils.i(comp.get("segments"), 48));
                AssemblyCompilerUtils.copy(comp, o, "angleDeg");
                AssemblyCompilerUtils.copy(comp, o, "angle");
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "connectSamples");
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "LOFT_SURFACE", "LOFT", "SKIN_SURFACE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "LOFT_SURFACE");
                AssemblyCompilerUtils.copy(comp, o, "sections");
                AssemblyCompilerUtils.copyInt(comp, o, "uSamples", AssemblyCompilerUtils.i(comp.get("uSamples"), AssemblyCompilerUtils.i(comp.get("u"), 24)));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "connectSamples");
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "SURFACE_OFFSET", "OFFSET_SURFACE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SURFACE_OFFSET");
                AssemblyCompilerUtils.copy(comp, o, "source");
                AssemblyCompilerUtils.copyInt(comp, o, "uSamples", AssemblyCompilerUtils.i(comp.get("uSamples"), AssemblyCompilerUtils.i(comp.get("u"), 24)));
                AssemblyCompilerUtils.copyInt(comp, o, "vSamples", AssemblyCompilerUtils.i(comp.get("vSamples"), AssemblyCompilerUtils.i(comp.get("v"), 24)));
                AssemblyCompilerUtils.copyInt(comp, o, "offset", AssemblyCompilerUtils.i(comp.get("offset"), AssemblyCompilerUtils.i(comp.get("distance"), 0)));
                AssemblyCompilerUtils.copyInt(comp, o, "shellThickness", AssemblyCompilerUtils.i(comp.get("shellThickness"), AssemblyCompilerUtils.i(comp.get("shell_thickness"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copy(comp, o, "mode");
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "IMPLICIT_FIELD", "IMPLICIT" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "IMPLICIT_FIELD");
                AssemblyCompilerUtils.copy(comp, o, "kind");
                AssemblyCompilerUtils.copy(comp, o, "field");
                AssemblyCompilerUtils.copy(comp, o, "center");
                AssemblyCompilerUtils.copy(comp, o, "metaballs");
                AssemblyCompilerUtils.copyInt(comp, o, "x0", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "x1", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "y0", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "y1", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "z0", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "z1", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "w", AssemblyCompilerUtils.i(comp.get("w"), AssemblyCompilerUtils.i(comp.get("width"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "d", AssemblyCompilerUtils.i(comp.get("d"), AssemblyCompilerUtils.i(comp.get("depth"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "h", AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copy(comp, o, "cx"); AssemblyCompilerUtils.copy(comp, o, "cy"); AssemblyCompilerUtils.copy(comp, o, "cz");
                AssemblyCompilerUtils.copy(comp, o, "r"); AssemblyCompilerUtils.copy(comp, o, "radius");
                AssemblyCompilerUtils.copy(comp, o, "R"); AssemblyCompilerUtils.copy(comp, o, "majorR");
                AssemblyCompilerUtils.copy(comp, o, "r2"); AssemblyCompilerUtils.copy(comp, o, "minorR");
                AssemblyCompilerUtils.copy(comp, o, "iso");
                AssemblyCompilerUtils.copy(comp, o, "band"); AssemblyCompilerUtils.copy(comp, o, "thickness");
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "MARCHING_CUBES", "MARCHING" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "MARCHING_CUBES");
                AssemblyCompilerUtils.copy(comp, o, "kind");
                AssemblyCompilerUtils.copy(comp, o, "field");
                AssemblyCompilerUtils.copy(comp, o, "center");
                AssemblyCompilerUtils.copy(comp, o, "metaballs");
                AssemblyCompilerUtils.copyInt(comp, o, "x0", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "x1", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "y0", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "y1", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "z0", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "z1", Integer.MIN_VALUE);
                AssemblyCompilerUtils.copyInt(comp, o, "w", AssemblyCompilerUtils.i(comp.get("w"), AssemblyCompilerUtils.i(comp.get("width"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "d", AssemblyCompilerUtils.i(comp.get("d"), AssemblyCompilerUtils.i(comp.get("depth"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "h", AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copy(comp, o, "cx"); AssemblyCompilerUtils.copy(comp, o, "cy"); AssemblyCompilerUtils.copy(comp, o, "cz");
                AssemblyCompilerUtils.copy(comp, o, "r"); AssemblyCompilerUtils.copy(comp, o, "radius");
                AssemblyCompilerUtils.copy(comp, o, "R"); AssemblyCompilerUtils.copy(comp, o, "majorR");
                AssemblyCompilerUtils.copy(comp, o, "r2"); AssemblyCompilerUtils.copy(comp, o, "minorR");
                AssemblyCompilerUtils.copy(comp, o, "iso");
                AssemblyCompilerUtils.copyInt(comp, o, "fill", AssemblyCompilerUtils.i(comp.get("fill"), AssemblyCompilerUtils.i(comp.get("samples"), 2)));
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "BUTTRESS", "FLYING_BUTTRESS" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "BUTTRESS");
                AssemblyCompilerUtils.copy(comp, o, "from");
                AssemblyCompilerUtils.copy(comp, o, "to");
                AssemblyCompilerUtils.copyInt(comp, o, "rise", AssemblyCompilerUtils.i(comp.get("rise"), AssemblyCompilerUtils.i(comp.get("sagitta"), -1)));
                AssemblyCompilerUtils.copyInt(comp, o, "samples", AssemblyCompilerUtils.i(comp.get("samples"), AssemblyCompilerUtils.i(comp.get("steps"), -1)));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copyInt(comp, o, "pierDown", AssemblyCompilerUtils.i(comp.get("pierDown"), AssemblyCompilerUtils.i(comp.get("pier_down"), 6)));
                AssemblyCompilerUtils.copy(comp, o, "rib");
                AssemblyCompilerUtils.copy(comp, o, "pier");
                AssemblyCompilerUtils.copy(comp, o, "joint");
                ops.add(o);
            }
            case "ARCH_RIB", "ARCH", "RIB_ARCH" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ARCH_RIB");
                AssemblyCompilerUtils.copy(comp, o, "from");
                AssemblyCompilerUtils.copy(comp, o, "to");
                AssemblyCompilerUtils.copyInt(comp, o, "rise", AssemblyCompilerUtils.i(comp.get("rise"), AssemblyCompilerUtils.i(comp.get("sagitta"), -1)));
                AssemblyCompilerUtils.copyInt(comp, o, "samples", AssemblyCompilerUtils.i(comp.get("samples"), AssemblyCompilerUtils.i(comp.get("steps"), -1)));
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "TRUSS_2D", "TRUSS", "TRUSS2D" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "TRUSS_2D");
                AssemblyCompilerUtils.copy(comp, o, "from");
                AssemblyCompilerUtils.copy(comp, o, "to");
                AssemblyCompilerUtils.copyInt(comp, o, "height", AssemblyCompilerUtils.i(comp.get("height"), AssemblyCompilerUtils.i(comp.get("h"), 6)));
                AssemblyCompilerUtils.copyInt(comp, o, "module", AssemblyCompilerUtils.i(comp.get("module"), AssemblyCompilerUtils.i(comp.get("step"), 4)));
                AssemblyCompilerUtils.copy(comp, o, "pattern");
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", AssemblyCompilerUtils.i(comp.get("thickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "chord");
                AssemblyCompilerUtils.copy(comp, o, "web");
                AssemblyCompilerUtils.copy(comp, o, "joint");
                ops.add(o);
            }
            case "SPLINE_SWEEP", "SWEEP_SPLINE", "SPLINE_TUBE", "SPLINE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SPLINE_SWEEP");
                // points required
                AssemblyCompilerUtils.copy(comp, o, "points");
                AssemblyCompilerUtils.copy(comp, o, "profile");
                AssemblyCompilerUtils.copy(comp, o, "profileFrame");
                AssemblyCompilerUtils.copy(comp, o, "profileSnap");
                AssemblyCompilerUtils.copy(comp, o, "frame");
                AssemblyCompilerUtils.copy(comp, o, "snap");
                AssemblyCompilerUtils.copyInt(comp, o, "profileW", AssemblyCompilerUtils.i(comp.get("profileW"), AssemblyCompilerUtils.i(comp.get("w"), 5)));
                AssemblyCompilerUtils.copyInt(comp, o, "profileH", AssemblyCompilerUtils.i(comp.get("profileH"), AssemblyCompilerUtils.i(comp.get("h"), 3)));
                AssemblyCompilerUtils.copy(comp, o, "profilePoints");
                AssemblyCompilerUtils.copy(comp, o, "profileRings");
                AssemblyCompilerUtils.copy(comp, o, "rings");
                AssemblyCompilerUtils.copy(comp, o, "profileScale0");
                AssemblyCompilerUtils.copy(comp, o, "profileScale1");
                AssemblyCompilerUtils.copy(comp, o, "scale0");
                AssemblyCompilerUtils.copy(comp, o, "scale1");
                // RECT taper (optional)
                AssemblyCompilerUtils.copyInt(comp, o, "profileW0", AssemblyCompilerUtils.i(comp.get("profileW0"), AssemblyCompilerUtils.i(comp.get("w0"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "profileW1", AssemblyCompilerUtils.i(comp.get("profileW1"), AssemblyCompilerUtils.i(comp.get("w1"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "profileH0", AssemblyCompilerUtils.i(comp.get("profileH0"), AssemblyCompilerUtils.i(comp.get("h0"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "profileH1", AssemblyCompilerUtils.i(comp.get("profileH1"), AssemblyCompilerUtils.i(comp.get("h1"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copy(comp, o, "twistTurns");
                AssemblyCompilerUtils.copy(comp, o, "twistPhase");
                AssemblyCompilerUtils.copy(comp, o, "capEnds");
                AssemblyCompilerUtils.copy(comp, o, "carveInterior");
                AssemblyCompilerUtils.copyInt(comp, o, "capThickness", AssemblyCompilerUtils.i(comp.get("capThickness"), 1));
                AssemblyCompilerUtils.copy(comp, o, "connectSamples");
                AssemblyCompilerUtils.copyInt(comp, o, "connectMaxStep", AssemblyCompilerUtils.i(comp.get("connectMaxStep"), 2));
                // radius/taper
                AssemblyCompilerUtils.copyInt(comp, o, "r", AssemblyCompilerUtils.i(comp.get("r"), AssemblyCompilerUtils.i(comp.get("radius"), 3)));
                AssemblyCompilerUtils.copyInt(comp, o, "r0", AssemblyCompilerUtils.i(comp.get("r0"), AssemblyCompilerUtils.i(comp.get("radius0"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copyInt(comp, o, "r1", AssemblyCompilerUtils.i(comp.get("r1"), AssemblyCompilerUtils.i(comp.get("radius1"), Integer.MIN_VALUE)));
                AssemblyCompilerUtils.copy(comp, o, "hollow");
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", 1);
                AssemblyCompilerUtils.copyInt(comp, o, "samplesPerBlock", 10);
                AssemblyCompilerUtils.copy(comp, o, "material");
                AssemblyCompilerUtils.copy(comp, o, "wall");
                ops.add(o);
            }
            case "CYLINDER" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "CYLINDER");
                // allow r/radius, h/height
                AssemblyCompilerUtils.copyInt(comp, o, "r", AssemblyCompilerUtils.i(comp.get("r"), AssemblyCompilerUtils.i(comp.get("radius"), 6)));
                AssemblyCompilerUtils.copyInt(comp, o, "h", AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), 18)));
                AssemblyCompilerUtils.copy(comp, o, "hollow");
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", 1);
                AssemblyCompilerUtils.copy(comp, o, "material");
                AssemblyCompilerUtils.copy(comp, o, "wall");
                ops.add(o);
            }
            case "EXTRUDE_POLYGON", "EXTRUDE" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "EXTRUDE_POLYGON");
                AssemblyCompilerUtils.copy(comp, o, "shape");
                AssemblyCompilerUtils.copyInt(comp, o, "w", 11);
                AssemblyCompilerUtils.copyInt(comp, o, "d", 11);
                AssemblyCompilerUtils.copyInt(comp, o, "h", AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), 12)));
                AssemblyCompilerUtils.copy(comp, o, "points");
                AssemblyCompilerUtils.copy(comp, o, "hollow");
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", 1);
                AssemblyCompilerUtils.copy(comp, o, "material");
                AssemblyCompilerUtils.copy(comp, o, "wall");
                ops.add(o);
            }
            case "ROOF_COVER", "ROOF" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "ROOF_COVER");
                // Roof shape kind (FLAT/GABLE). Component "type" is reserved for component kind.
                String roofType = AssemblyCompilerUtils.str(
                        comp.get("roofType"),
                        AssemblyCompilerUtils.str(comp.get("coverType"),
                                AssemblyCompilerUtils.str(comp.get("cover"),
                                        AssemblyCompilerUtils.str(comp.get("roof_cover_type"),
                                                AssemblyCompilerUtils.str(comp.get("kind"), "GABLE")
                                        )
                                )
                        )
                );
                if (roofType != null) o.put("type", roofType);
                AssemblyCompilerUtils.copyInt(comp, o, "w", 11);
                AssemblyCompilerUtils.copyInt(comp, o, "d", 11);
                AssemblyCompilerUtils.copyInt(comp, o, "y", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "overhang", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "rise", AssemblyCompilerUtils.i(comp.get("rise"), AssemblyCompilerUtils.i(comp.get("height"), 4)));
                AssemblyCompilerUtils.copy(comp, o, "roof");
                AssemblyCompilerUtils.copy(comp, o, "slab");
                ops.add(o);
            }
            case "CONNECTOR_LINE", "CONNECTOR" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "CONNECTOR_LINE");
                // from/to can be map or x0..x1 etc
                AssemblyCompilerUtils.copy(comp, o, "from");
                AssemblyCompilerUtils.copy(comp, o, "to");
                AssemblyCompilerUtils.copyInt(comp, o, "x0", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "y0", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "z0", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "x1", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "y1", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "z1", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "thickness", 1);
                AssemblyCompilerUtils.copyInt(comp, o, "h", AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), 1)));
                AssemblyCompilerUtils.copy(comp, o, "material");
                ops.add(o);
            }
            case "BOX_SHELL", "SHELL_BOX" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SHELL_BOX");
                int w = AssemblyCompilerUtils.i(comp.get("w"), 15);
                int d = AssemblyCompilerUtils.i(comp.get("d"), 15);
                int h = AssemblyCompilerUtils.i(comp.get("h"), 18);
                o.put("w", w);
                o.put("d", d);
                o.put("h", h);
                AssemblyCompilerUtils.copyInt(comp, o, "floorStep", 4);
                // Forma-Gene integration: twist support
                AssemblyCompilerUtils.copy(comp, o, "twistTurns");
                AssemblyCompilerUtils.copy(comp, o, "twist_turns");
                AssemblyCompilerUtils.copy(comp, o, "twistPhase");
                AssemblyCompilerUtils.copy(comp, o, "twist_phase");
                // optional block overrides
                AssemblyCompilerUtils.copy(comp, o, "wall");
                AssemblyCompilerUtils.copy(comp, o, "window");
                AssemblyCompilerUtils.copy(comp, o, "floor");
                AssemblyCompilerUtils.copy(comp, o, "roof");
                ops.add(o);

                // Optional facade attachments (unstyled):
                // - surface patterns (ribs/stripes/grid)
                // - openings (window grids / doors)
                emitFacadeOpsForBox(ops, comp, w, d, h);
            }
            case "BSP_INTERIOR", "BSP_FLOOR_PLAN" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "BSP_FLOOR_PLAN");
                AssemblyCompilerUtils.copyInt(comp, o, "w", 19);
                AssemblyCompilerUtils.copyInt(comp, o, "d", 19);
                AssemblyCompilerUtils.copyInt(comp, o, "h", 30);
                Object cfg = comp.get("config");
                if (cfg == null) cfg = comp.get("floor_plan_logic");
                if (cfg == null) cfg = comp.get("floorPlanLogic");
                if (cfg != null) o.put("config", cfg);
                // optional semantic overrides
                AssemblyCompilerUtils.copy(comp, o, "coreWall");
                AssemblyCompilerUtils.copy(comp, o, "roomWall");
                AssemblyCompilerUtils.copy(comp, o, "roomWallOpen");
                AssemblyCompilerUtils.copy(comp, o, "stairs");
                ops.add(o);
            }
            case "CLEAR_BOX", "CARVE_BOX" -> {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "CLEAR_BOX");
                AssemblyCompilerUtils.copyInt(comp, o, "x0", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "y0", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "z0", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "x1", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "y1", 0);
                AssemblyCompilerUtils.copyInt(comp, o, "z1", 0);
                ops.add(o);
            }
            default -> {
                // unknown component: ignore for forward compatibility
            }
        }

        ops.add(AssemblyCompilerUtils.op("POP_ORIGIN"));
    }

    @SuppressWarnings("unchecked")
    static void emitFacadeOpsForBox(List<Map<String, Object>> ops, Map<String, Object> comp, int w, int d, int h) {
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
            String faces = AssemblyCompilerUtils.str(spx.get("face"), AssemblyCompilerUtils.str(spx.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
            String pattern = AssemblyCompilerUtils.str(spx.get("type"), AssemblyCompilerUtils.str(spx.get("pattern"), "GRID")).trim().toUpperCase(Locale.ROOT);
            int step = Math.max(1, AssemblyCompilerUtils.i(spx.get("step"), AssemblyCompilerUtils.i(spx.get("spacing"), 3)));
            int thickness = Math.max(1, AssemblyCompilerUtils.i(spx.get("thickness"), 1));

            for (String face : expandFaces(faces)) {
                Map<String, Object> o = new HashMap<>();
                o.put("op", "SURFACE_PATTERN");
                o.put("face", face);
                o.put("pattern", pattern);
                if (!pattern.equals("NOISE")) {
                    o.put("step", step);
                    o.put("thickness", thickness);
                }
                // bounds
                o.put("x0", -hx); o.put("x1", hx);
                o.put("y0", y0);  o.put("y1", y1);
                o.put("z0", -hz); o.put("z1", hz);
                // material overrides: material/frame/accent accepted by engine pick()
                AssemblyCompilerUtils.copy(spx, o, "material");
                AssemblyCompilerUtils.copy(spx, o, "accent");
                AssemblyCompilerUtils.copy(spx, o, "frame");
                // Forma-Gene integration: NOISE pattern parameters
                if (pattern.equals("NOISE")) {
                    AssemblyCompilerUtils.copy(spx, o, "noiseMaterial");
                    AssemblyCompilerUtils.copy(spx, o, "noise_material");
                    AssemblyCompilerUtils.copy(spx, o, "noiseProbability");
                    AssemblyCompilerUtils.copy(spx, o, "noise_probability");
                    AssemblyCompilerUtils.copy(spx, o, "noiseMethod");
                    AssemblyCompilerUtils.copy(spx, o, "noise_method");
                }
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

                String faces = AssemblyCompilerUtils.str(oo.get("face"), AssemblyCompilerUtils.str(oo.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
                String kind = AssemblyCompilerUtils.str(oo.get("kind"), AssemblyCompilerUtils.str(oo.get("type"), "WINDOW_GRID")).trim().toUpperCase(Locale.ROOT);

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
                    AssemblyCompilerUtils.copyInt(oo, o, "rows", AssemblyCompilerUtils.i(oo.get("rows"), 2));
                    AssemblyCompilerUtils.copyInt(oo, o, "cols", AssemblyCompilerUtils.i(oo.get("cols"), 3));
                    AssemblyCompilerUtils.copyInt(oo, o, "winW", AssemblyCompilerUtils.i(oo.get("winW"), AssemblyCompilerUtils.i(oo.get("w"), 2)));
                    AssemblyCompilerUtils.copyInt(oo, o, "winH", AssemblyCompilerUtils.i(oo.get("winH"), AssemblyCompilerUtils.i(oo.get("h"), 3)));
                    AssemblyCompilerUtils.copyInt(oo, o, "sillY", AssemblyCompilerUtils.i(oo.get("sillY"), 2));
                    AssemblyCompilerUtils.copyInt(oo, o, "marginX", AssemblyCompilerUtils.i(oo.get("marginX"), 2));
                    AssemblyCompilerUtils.copyInt(oo, o, "marginY", AssemblyCompilerUtils.i(oo.get("marginY"), 2));
                    AssemblyCompilerUtils.copyInt(oo, o, "gapX", AssemblyCompilerUtils.i(oo.get("gapX"), 2));
                    AssemblyCompilerUtils.copyInt(oo, o, "gapY", AssemblyCompilerUtils.i(oo.get("gapY"), 2));
                    AssemblyCompilerUtils.copyInt(oo, o, "frameThickness", AssemblyCompilerUtils.i(oo.get("frameThickness"), 1));
                    AssemblyCompilerUtils.copyInt(oo, o, "mullionStep", AssemblyCompilerUtils.i(oo.get("mullionStep"), 0));
                    // arch/rose parameters (optional)
                    AssemblyCompilerUtils.copy(oo, o, "archType");
                    AssemblyCompilerUtils.copy(oo, o, "arch");
                    AssemblyCompilerUtils.copyInt(oo, o, "archThickness", AssemblyCompilerUtils.i(oo.get("archThickness"), AssemblyCompilerUtils.i(oo.get("archT"), 0)));
                    AssemblyCompilerUtils.copy(oo, o, "keystone");
                    AssemblyCompilerUtils.copy(oo, o, "keystoneOn");
                    AssemblyCompilerUtils.copy(oo, o, "tracery");
                    AssemblyCompilerUtils.copy(oo, o, "traceryType");
                    AssemblyCompilerUtils.copy(oo, o, "traceryMaterial");
                    AssemblyCompilerUtils.copyInt(oo, o, "traceryThickness", AssemblyCompilerUtils.i(oo.get("traceryThickness"), AssemblyCompilerUtils.i(oo.get("traceryT"), 0)));
                    AssemblyCompilerUtils.copyInt(oo, o, "traceryY", AssemblyCompilerUtils.i(oo.get("traceryY"), Integer.MIN_VALUE));
                    AssemblyCompilerUtils.copyInt(oo, o, "traceryInset", AssemblyCompilerUtils.i(oo.get("traceryInset"), 0));
                    AssemblyCompilerUtils.copyInt(oo, o, "traceryFoilRadius", AssemblyCompilerUtils.i(oo.get("traceryFoilRadius"), AssemblyCompilerUtils.i(oo.get("foilRadius"), 0)));
                    AssemblyCompilerUtils.copyInt(oo, o, "traceryFoilCenterY", AssemblyCompilerUtils.i(oo.get("traceryFoilCenterY"), AssemblyCompilerUtils.i(oo.get("foilCenterY"), Integer.MIN_VALUE)));
                    AssemblyCompilerUtils.copyInt(oo, o, "traceryFoilCount", AssemblyCompilerUtils.i(oo.get("traceryFoilCount"), AssemblyCompilerUtils.i(oo.get("foilCount"), 1)));
                    AssemblyCompilerUtils.copyInt(oo, o, "traceryFoilStepY", AssemblyCompilerUtils.i(oo.get("traceryFoilStepY"), AssemblyCompilerUtils.i(oo.get("foilStepY"), AssemblyCompilerUtils.i(oo.get("foilGapY"), 0))));
                    // Also copy raw (string-friendly) knobs so LLM can use e.g. foilCount="AUTO"
                    AssemblyCompilerUtils.copy(oo, o, "foilCount");
                    AssemblyCompilerUtils.copy(oo, o, "traceryFoilCount");
                    AssemblyCompilerUtils.copy(oo, o, "foilStepY");
                    AssemblyCompilerUtils.copy(oo, o, "foilGapY");
                    AssemblyCompilerUtils.copy(oo, o, "traceryFoilStepY");
                    AssemblyCompilerUtils.copy(oo, o, "foilCenterY");
                    AssemblyCompilerUtils.copy(oo, o, "traceryFoilCenterY");
                    AssemblyCompilerUtils.copyInt(oo, o, "r", AssemblyCompilerUtils.i(oo.get("r"), AssemblyCompilerUtils.i(oo.get("radius"), 0)));
                    AssemblyCompilerUtils.copyInt(oo, o, "centerY", AssemblyCompilerUtils.i(oo.get("centerY"), -999999));
                    AssemblyCompilerUtils.copyInt(oo, o, "petals", AssemblyCompilerUtils.i(oo.get("petals"), AssemblyCompilerUtils.i(oo.get("spokes"), 0)));
                    AssemblyCompilerUtils.copyInt(oo, o, "ring", AssemblyCompilerUtils.i(oo.get("ring"), 0));
                    AssemblyCompilerUtils.copy(oo, o, "phase");
                    AssemblyCompilerUtils.copy(oo, o, "phi");
                    AssemblyCompilerUtils.copyInt(oo, o, "spokeWidth", AssemblyCompilerUtils.i(oo.get("spokeWidth"), AssemblyCompilerUtils.i(oo.get("spokeW"), 0)));
                    AssemblyCompilerUtils.copy(oo, o, "spokeThreshold");
                    AssemblyCompilerUtils.copy(oo, o, "spokeThresh");
                    AssemblyCompilerUtils.copy(oo, o, "innerFill");
                    AssemblyCompilerUtils.copy(oo, o, "spokeMaterial");
                    // materials
                    AssemblyCompilerUtils.copy(oo, o, "fill");
                    AssemblyCompilerUtils.copy(oo, o, "frame");
                    AssemblyCompilerUtils.copy(oo, o, "air"); // allow override of "air" behavior (e.g., open hole)
                    ops.add(o);
                }
            }
        }

        // -------- decorative elements (asset library) --------
        Object decoObj = facade.get("decorativeElements");
        if (decoObj == null) decoObj = facade.get("decorative_elements");
        if (decoObj instanceof List<?> decoList) {
            for (Object item : decoList) {
                if (!(item instanceof Map<?, ?> im)) continue;
                Map<String, Object> deco;
                try { deco = (Map<String, Object>) im; } catch (Exception e) { continue; }
                
                String assetId = AssemblyCompilerUtils.str(deco.get("assetId"), "");
                if (assetId.isEmpty()) continue;
                
                String placement = AssemblyCompilerUtils.str(deco.get("placement"), "BOTTOM_CENTER");
                String faces = AssemblyCompilerUtils.str(deco.get("face"), AssemblyCompilerUtils.str(deco.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
                String type = AssemblyCompilerUtils.str(deco.get("type"), "FILLER");
                
                for (String face : expandFaces(faces)) {
                    Map<String, Object> o = new HashMap<>();
                    o.put("op", "PLACE_ASSET");
                    o.put("assetId", assetId);
                    o.put("face", face);
                    o.put("placement", placement);
                    o.put("type", type);
                    // bounds (for placement calculation)
                    o.put("x0", -hx); o.put("x1", hx);
                    o.put("y0", y0);  o.put("y1", y1);
                    o.put("z0", -hz); o.put("z1", hz);
                    // Optional parameters
                    AssemblyCompilerUtils.copy(deco, o, "parameters");
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
            String faces = AssemblyCompilerUtils.str(fgx.get("face"), AssemblyCompilerUtils.str(fgx.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
            int bayW = Math.max(1, AssemblyCompilerUtils.i(fgx.get("bayW"), AssemblyCompilerUtils.i(fgx.get("moduleW"), AssemblyCompilerUtils.i(fgx.get("gridW"), 3))));
            int bayH = Math.max(1, AssemblyCompilerUtils.i(fgx.get("bayH"), AssemblyCompilerUtils.i(fgx.get("moduleH"), AssemblyCompilerUtils.i(fgx.get("gridH"), 4))));
            int mullionT = Math.max(0, AssemblyCompilerUtils.i(fgx.get("mullionThickness"), AssemblyCompilerUtils.i(fgx.get("mullionT"), 1)));
            int transomT = Math.max(0, AssemblyCompilerUtils.i(fgx.get("transomThickness"), AssemblyCompilerUtils.i(fgx.get("transomT"), 1)));
            int borderT = Math.max(0, AssemblyCompilerUtils.i(fgx.get("borderThickness"), AssemblyCompilerUtils.i(fgx.get("borderT"), mullionT)));
            int marginU = Math.max(0, AssemblyCompilerUtils.i(fgx.get("marginU"), AssemblyCompilerUtils.i(fgx.get("marginX"), 1)));
            int marginY = Math.max(0, AssemblyCompilerUtils.i(fgx.get("marginY"), 1));
            int inset = Math.max(0, AssemblyCompilerUtils.i(fgx.get("inset"), 0));
            int depth = Math.max(1, AssemblyCompilerUtils.i(fgx.get("depth"), 1));
            // spandrel zones (optional)
            int spEvery = Math.max(0, AssemblyCompilerUtils.i(fgx.get("spandrelEvery"), AssemblyCompilerUtils.i(fgx.get("spEvery"), 0)));
            int spH = Math.max(0, AssemblyCompilerUtils.i(fgx.get("spandrelHeight"), AssemblyCompilerUtils.i(fgx.get("spH"), 0)));
            int spOff = Math.max(0, AssemblyCompilerUtils.i(fgx.get("spandrelOffset"), AssemblyCompilerUtils.i(fgx.get("spOffset"), 0)));

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
                AssemblyCompilerUtils.copy(fgx, o, "frame");
                AssemblyCompilerUtils.copy(fgx, o, "fill");
                AssemblyCompilerUtils.copy(fgx, o, "spandrelFill");
                AssemblyCompilerUtils.copy(fgx, o, "material"); // allow "material" alias for frame in engine pick()
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
            String faces = AssemblyCompilerUtils.str(sbx.get("face"), AssemblyCompilerUtils.str(sbx.get("faces"), "ALL")).trim().toUpperCase(Locale.ROOT);
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

    static List<String> expandFaces(String faces) {
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

    static int componentHeight(Map<String, Object> comp) {
        if (comp == null) return 0;
        String type = AssemblyCompilerUtils.str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = AssemblyCompilerUtils.str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);
        if (type.contains("ROOF")) {
            // ROOF_COVER: treat rise as its vertical extent for port inference
            int rise = AssemblyCompilerUtils.i(comp.get("rise"), AssemblyCompilerUtils.i(comp.get("height"), 0));
            int baseY = AssemblyCompilerUtils.i(comp.get("y"), 0);
            if (rise > 0) return baseY + rise;
            return baseY;
        }
        // common fields
        int h = AssemblyCompilerUtils.i(comp.get("h"), AssemblyCompilerUtils.i(comp.get("height"), 0));
        return Math.max(h, 0);
        // cylinder uses height too
    }

    static int componentWidth(Map<String, Object> comp) {
        if (comp == null) return 0;
        return AssemblyCompilerUtils.i(comp.get("w"), AssemblyCompilerUtils.i(comp.get("width"), 0));
    }

    static int componentDepth(Map<String, Object> comp) {
        if (comp == null) return 0;
        return AssemblyCompilerUtils.i(comp.get("d"), AssemblyCompilerUtils.i(comp.get("depth"), 0));
    }

    static int componentRadius(Map<String, Object> comp) {
        if (comp == null) return 0;
        return AssemblyCompilerUtils.i(comp.get("r"), AssemblyCompilerUtils.i(comp.get("radius"), 0));
    }

    static int[] componentOrigin(Map<String, Object> comp) {
        int dx = 0, dy = 0, dz = 0;
        Object at = comp != null ? comp.get("at") : null;
        if (at instanceof Map<?, ?> am) {
            dx = AssemblyCompilerUtils.i(am.get("x"), 0);
            dy = AssemblyCompilerUtils.i(am.get("y"), 0);
            dz = AssemblyCompilerUtils.i(am.get("z"), 0);
        } else if (comp != null) {
            dx = AssemblyCompilerUtils.i(comp.get("x"), 0);
            dy = AssemblyCompilerUtils.i(comp.get("y"), 0);
            dz = AssemblyCompilerUtils.i(comp.get("z"), 0);
        }
        return new int[]{dx, dy, dz};
    }

    static Map<String, int[]> buildPorts(Map<String, Object> comp) {
        Map<String, int[]> ports = new HashMap<>();
        // built-ins
        ports.put("center", new int[]{0, 0, 0});
        ports.put("bottom_center", new int[]{0, 0, 0});
        ports.put("top_center", new int[]{0, componentHeight(comp), 0});
        // common aliases
        ports.put("bottom", ports.get("bottom_center"));
        ports.put("top", ports.get("top_center"));
        ports.put("mid", ports.get("center"));

        String type = AssemblyCompilerUtils.str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = AssemblyCompilerUtils.str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);

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
            String roofCoverType = AssemblyCompilerUtils.str(
                    comp.get("roofType"),
                    AssemblyCompilerUtils.str(comp.get("coverType"),
                            AssemblyCompilerUtils.str(comp.get("cover"),
                                    AssemblyCompilerUtils.str(comp.get("roof_cover_type"),
                                            AssemblyCompilerUtils.str(comp.get("kind"), "GABLE")
                                    )
                            )
                    )
            ).trim().toUpperCase(Locale.ROOT);

            if (roofCoverType.contains("GABLE")) {
                int w = componentWidth(comp);
                int d = componentDepth(comp);
                int overhang = AssemblyCompilerUtils.i(comp.get("overhang"), 0);
                int baseY = AssemblyCompilerUtils.i(comp.get("y"), 0);
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
                    int x = AssemblyCompilerUtils.i(m.get("x"), 0);
                    int y = AssemblyCompilerUtils.i(m.get("y"), 0);
                    int z = AssemblyCompilerUtils.i(m.get("z"), 0);
                    ports.put(AssemblyCompilerUtils.normalizePortKey(name), new int[]{x, y, z});
                }
            }
        }
        return ports;
    }

    static int[] readPoint(Object v) {
        if (!(v instanceof Map<?, ?> pm)) return null;
        int x = AssemblyCompilerUtils.i(pm.get("x"), 0);
        int y = AssemblyCompilerUtils.i(pm.get("y"), 0);
        int z = AssemblyCompilerUtils.i(pm.get("z"), 0);
        return new int[]{x, y, z};
    }

    static int[] polygonBounds(Map<String, Object> comp) {
        if (comp == null) return null;
        Object ptsObj = comp.get("points");
        if (!(ptsObj instanceof List<?> pts)) return null;
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        boolean any = false;
        for (Object p : pts) {
            if (p instanceof Map<?, ?> pm) {
                int x = AssemblyCompilerUtils.i(pm.get("x"), 0);
                int z = AssemblyCompilerUtils.i(pm.get("z"), 0);
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

    static void alias(Map<String, int[]> ports, String alias, String target) {
        if (ports == null) return;
        int[] t = ports.get(AssemblyCompilerUtils.normalizePortKey(target));
        if (t != null) ports.put(AssemblyCompilerUtils.normalizePortKey(alias), t);
    }

    static List<?> generateBasicComponentFromMacro(Map<String, Object> m, Map<String, Object> graph, 
                                                              com.formacraft.common.model.build.BuildingSpec spec) {
        Object macroObj = m.get("macro");
        if (!(macroObj instanceof Map<?, ?>)) return null;
        Map<String, Object> macro = AssemblyCompilerUtils.safeMap((Map<?, ?>) macroObj);
        if (macro == null) return null;

        // 从 BuildingSpec 获取尺寸
        int w = 20, d = 30, h = 15;
        if (spec != null) {
            if (spec.getFootprint() != null) {
                Integer width = spec.getFootprint().getWidth();
                Integer depth = spec.getFootprint().getDepth();
                if (width != null && width > 0) w = width;
                if (depth != null && depth > 0) d = depth;
            }
            if (spec.getHeight() > 0) h = spec.getHeight();
        }
        
        // 创建基本 SHELL_BOX 组件
        Map<String, Object> basicComponent = new HashMap<>();
        basicComponent.put("id", "MainVolume");
        basicComponent.put("type", "SHELL_BOX");
        basicComponent.put("w", w);
        basicComponent.put("d", d);
        basicComponent.put("h", h);
        
        List<Object> comps = new ArrayList<>();
        comps.add(basicComponent);
        
        // 更新 graph 或 root
        if (graph != null) {
            graph.put("components", comps);
        } else {
            m.put("components", comps);
        }
        
        return comps;
    }
}
