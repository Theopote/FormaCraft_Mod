package com.formacraft.server.assembly.validation;

import org.jetbrains.annotations.NotNull;

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
                "macro",
                "ops",
                "graph", "components", "connections"
        ));
        // macro validation (P0)
        Object macroObj = root.get("macro");
        if (macroObj instanceof Map<?, ?> mm) {
            validateMacro(out, mm);
        } else if (macroObj != null) {
            out.add(err("$.macro", "E_MACRO_TYPE", "macro 必须是对象（map）"));
        }

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
                    // truss
                    "chord", "web", "joint", "pattern", "module", "step",
                    // arch rib
                    "from", "to", "rise", "sagitta", "samples", "steps",
                    // buttress
                    "rib", "pier", "pierDown", "pier_down",
                    // cable
                    "sag", "droop",
                    "hangersEvery", "hangerEvery",
                    "hangersToY", "hangerToY",
                    "hangersMaterial",
                    "cableCount", "count",
                    "cableSpacing", "spacing",
                    "cableAxis",
                    // frame grid 3d
                    "stepX", "stepY", "stepZ",
                    "sx", "sy", "sz",
                    "mode",
                    "diagonal",
                    // stair system
                    "clearHeight", "clear_h",
                    "carve",
                    "support",
                    "stairs",
                    "supportMaterial",
                    // anchoring / anchorage
                    "yBase",
                    "maxDepth", "anchorDepth",
                    "stopOnSolid",
                    "allowWaterEdit", "allowLavaEdit",
                    "solid",
                    // anchorage detailing
                    "topBevel", "top_bevel", "bevel",
                    "guardWallHeight", "guard_wall_height", "parapetHeight",
                    "guardWallInset", "guard_wall_inset",
                    "guardWallCrenels", "guard_wall_crenels", "crenels",
                    "guardWallMaterial", "guardWall",
                    "holes", "cableHoles",
                    // generic knobs
                    "type", "kind", "face", "faces",
                    "w", "d", "h", "width", "depth", "height",
                    // spline
                    "points", "profile", "profileFrame", "profileSnap", "snap",
                    "profileW", "profileH", "profilePoints", "profileRings", "rings",
                    "profileScale0", "profileScale1", "scale0", "scale1",
                    "profileW0", "profileW1", "profileH0", "profileH1",
                    "twistTurns", "twistPhase",
                    "capEnds", "capThickness", "carveInterior",
                    "connectSamples", "connectMaxStep",
                    // bezier surface
                    "uSamples", "vSamples", "u", "v",
                    // loft / revolve
                    "sections", "segments", "angleDeg", "angle",
                    // bezier surface set
                    "patches", "grid", "topology", "stitch",
                    "stitchEpsilon", "stitch_eps",
                    "stitchSamples", "stitch_samples",
                    "stitchResampleMode", "stitch_resample_mode",
                    "capWidth", "cap_width",
                    "capMaterial", "cap_material",
                    // surface offset / implicit / marching
                    "source", "offset", "distance", "shellThickness", "shell_thickness",
                    "normalMode", "normal_mode",
                    "stepLen", "step_len",
                    "dedupe", "deDupe",
                    "connect_samples",
                    "connect_max_step",
                    "field", "center", "cx", "cy", "cz", "r", "radius", "R", "majorR", "r2", "minorR",
                    "metaballs", "iso", "band",
                    "r0", "r1", "radius0", "radius1",
                    "hollow", "thickness", "samplesPerBlock",
                    // openings
                    "rows", "cols", "winW", "winH", "sillY", "marginX", "marginY", "gapX", "gapY", "frameThickness", "mullionStep",
                    "doorW", "doorH",
                    "archType", "arch", "archThickness", "archT",
                    "keystone", "keystoneOn",
                    "tracery", "traceryType", "traceryMaterial", "traceryThickness", "traceryT", "traceryY", "traceryInset",
                    "foilRadius", "foilCenterY", "foilCount", "foilStepY", "foilGapY",
                    "traceryFoilRadius", "traceryFoilCenterY", "traceryFoilCount", "traceryFoilStepY",
                    "centerY", "petals", "spokes", "ring", "phase", "phi", "spokeWidth", "spokeW", "spokeThreshold", "spokeThresh",
                    "innerFill", "spokeMaterial",
                    // facade grid
                    "bayW", "bayH", "moduleW", "moduleH", "gridW", "gridH",
                    "mullionThickness", "mullionT", "transomThickness", "transomT", "borderThickness", "borderT",
                    "marginU", "inset",
                    "spandrelEvery", "spEvery", "spandrelHeight", "spH", "spandrelOffset", "spOffset", "spandrelFill",
                    // surface bands
                    "horizontalBands", "hBands", "bandsH",
                    "verticalBands", "vBands", "bandsV"
            ));
            String op = str(m.get("op"), "").trim().toUpperCase(Locale.ROOT);
            if (op.isBlank()) out.add(err(p + ".op", "E_OP_MISSING", "缺少 op 字段"));

            // Common structural ops
            if (op.equals("PUSH_ORIGIN")) {
                // dx/dy/dz optional (default 0) but should be ints if present
                if (m.get("dx") != null && intOrNull(m.get("dx")) == null) out.add(err(p + ".dx", "E_INT_TYPE", "dx 必须是整数"));
                if (m.get("dy") != null && intOrNull(m.get("dy")) == null) out.add(err(p + ".dy", "E_INT_TYPE", "dy 必须是整数"));
                if (m.get("dz") != null && intOrNull(m.get("dz")) == null) out.add(err(p + ".dz", "E_INT_TYPE", "dz 必须是整数"));
            }
            if (op.equals("POP_ORIGIN")) {
                // no required fields
            }
            if (op.equals("CLEAR_BOX")) {
                // require x0..z1 if present; allow defaults but enforce type
                requireIntIfPresent(out, p, m, "x0");
                requireIntIfPresent(out, p, m, "y0");
                requireIntIfPresent(out, p, m, "z0");
                requireIntIfPresent(out, p, m, "x1");
                requireIntIfPresent(out, p, m, "y1");
                requireIntIfPresent(out, p, m, "z1");
            }
            if (op.equals("SHELL_BOX")) {
                // Engine clamps but validator makes training stable
                requireIntMin(out, p, m, "w", 1);
                requireIntMin(out, p, m, "d", 1);
                requireIntMin(out, p, m, "h", 2);
                if (m.get("floorStep") != null) requireIntMin(out, p, m, "floorStep", 1);
            }
            if (op.equals("CYLINDER")) {
                if (m.get("r") != null || m.get("radius") != null) {
                    Integer rv = intOrNull(m.get("r"));
                    if (rv == null) rv = intOrNull(m.get("radius"));
                    if (rv == null) out.add(err(p + ".r", "E_INT_TYPE", "r/radius 必须是整数"));
                    else if (rv < 2) out.add(err(p + ".r", "E_INT_RANGE", "r/radius 必须 >= 2"));
                }
                if (m.get("h") != null || m.get("height") != null) {
                    Integer hv = intOrNull(m.get("h"));
                    if (hv == null) hv = intOrNull(m.get("height"));
                    if (hv == null) out.add(err(p + ".h", "E_INT_TYPE", "h/height 必须是整数"));
                    else if (hv < 1) out.add(err(p + ".h", "E_INT_RANGE", "h/height 必须 >= 1"));
                }
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
            }
            if (op.equals("CONNECTOR_LINE")) {
                // require endpoints
                validatePointRefXYZ(out, p + ".from", m.get("from"), "E_CONN_FROM_MISSING");
                validatePointRefXYZ(out, p + ".to", m.get("to"), "E_CONN_TO_MISSING");
                // or allow x0..z1 form (if from/to omitted)
                if (m.get("from") == null) {
                    requireIntIfPresent(out, p, m, "x0");
                    requireIntIfPresent(out, p, m, "y0");
                    requireIntIfPresent(out, p, m, "z0");
                }
                if (m.get("to") == null) {
                    requireIntIfPresent(out, p, m, "x1");
                    requireIntIfPresent(out, p, m, "y1");
                    requireIntIfPresent(out, p, m, "z1");
                }
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("h") != null || m.get("height") != null) requireIntMin(out, p, m, "h", 1);
            }
            if (op.equals("TRUSS_2D")) {
                validatePointRefXYZ(out, p + ".from", m.get("from"), "E_TRUSS_FROM_MISSING");
                validatePointRefXYZ(out, p + ".to", m.get("to"), "E_TRUSS_TO_MISSING");
                if (m.get("height") != null || m.get("h") != null) requireIntMin(out, p, m, "height", 1);
                if (m.get("module") != null || m.get("step") != null) requireIntMin(out, p, m, "module", 1);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("pattern") != null) {
                    String pat = String.valueOf(m.get("pattern")).trim().toUpperCase(Locale.ROOT);
                    if (!pat.isEmpty() && !(pat.contains("WARREN") || pat.contains("PRATT") || pat.contains("HOWE"))) {
                        out.add(warn(p + ".pattern", "W_TRUSS_PATTERN_VALUE", "TRUSS_2D.pattern 建议使用 WARREN/PRATT/HOWE（当前=" + pat + "）"));
                    }
                }
            }
            if (op.equals("ARCH_RIB")) {
                validatePointRefXYZ(out, p + ".from", m.get("from"), "E_ARCH_FROM_MISSING");
                validatePointRefXYZ(out, p + ".to", m.get("to"), "E_ARCH_TO_MISSING");
                if (m.get("rise") != null || m.get("sagitta") != null) requireIntMin(out, p, m, "rise", 1);
                if (m.get("samples") != null || m.get("steps") != null) requireIntMin(out, p, m, "samples", 2);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
            }
            if (op.equals("BUTTRESS")) {
                validatePointRefXYZ(out, p + ".from", m.get("from"), "E_BUTTRESS_FROM_MISSING");
                validatePointRefXYZ(out, p + ".to", m.get("to"), "E_BUTTRESS_TO_MISSING");
                if (m.get("rise") != null || m.get("sagitta") != null) requireIntMin(out, p, m, "rise", 1);
                if (m.get("samples") != null || m.get("steps") != null) requireIntMin(out, p, m, "samples", 2);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("pierDown") != null || m.get("pier_down") != null) requireIntMin(out, p, m, "pierDown", 0);
            }
            if (op.equals("TENSION_CABLE")) {
                validatePointRefXYZ(out, p + ".from", m.get("from"), "E_CABLE_FROM_MISSING");
                validatePointRefXYZ(out, p + ".to", m.get("to"), "E_CABLE_TO_MISSING");
                if (m.get("sag") != null || m.get("droop") != null) requireIntMin(out, p, m, "sag", 1);
                if (m.get("samples") != null || m.get("steps") != null) requireIntMin(out, p, m, "samples", 2);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("hangersEvery") != null || m.get("hangerEvery") != null) requireIntMin(out, p, m, "hangersEvery", 1);
                if (m.get("hangersToY") != null || m.get("hangerToY") != null) {
                    if (intOrNull(m.get("hangersToY")) == null && intOrNull(m.get("hangerToY")) == null) {
                        out.add(err(p + ".hangersToY", "E_INT_TYPE", "hangersToY 必须是整数"));
                    }
                }
                if (m.get("cableCount") != null || m.get("count") != null) requireIntMin(out, p, m, "cableCount", 1);
                if (m.get("cableSpacing") != null || m.get("spacing") != null) requireIntMin(out, p, m, "cableSpacing", 1);
                if (m.get("cableAxis") != null) {
                    String ax = String.valueOf(m.get("cableAxis")).trim().toUpperCase(Locale.ROOT);
                    if (!ax.isEmpty() && !(ax.equals("AUTO") || ax.equals("X") || ax.equals("Z"))) {
                        out.add(warn(p + ".cableAxis", "W_CABLE_AXIS_VALUE", "cableAxis 建议使用 AUTO/X/Z（当前=" + m.get("cableAxis") + "）"));
                    }
                }
            }
            if (op.equals("BEZIER_SURFACE")) {
                Object pts = m.get("points");
                if (!(pts instanceof List<?> list)) {
                    out.add(err(p + ".points", "E_BEZIER_POINTS_MISSING", "BEZIER_SURFACE.points 必须是数组（16 点或 4x4 网格）"));
                } else {
                    int count = 0;
                    if (!list.isEmpty() && list.getFirst() instanceof List<?>) {
                        for (Object rowObj : list) {
                            if (!(rowObj instanceof List<?> row)) continue;
                            for (Object po : row) {
                                if (po instanceof Map<?, ?> pm) {
                                    count++;
                                    if (intOrNull(pm.get("x")) == null) out.add(err(p + ".points", "E_BEZIER_POINT_X", "控制点 x 必须是整数"));
                                    if (intOrNull(pm.get("y")) == null) out.add(err(p + ".points", "E_BEZIER_POINT_Y", "控制点 y 必须是整数"));
                                    if (intOrNull(pm.get("z")) == null) out.add(err(p + ".points", "E_BEZIER_POINT_Z", "控制点 z 必须是整数"));
                                }
                            }
                        }
                    } else {
                        for (Object po : list) {
                            if (po instanceof Map<?, ?> pm) {
                                count++;
                                if (intOrNull(pm.get("x")) == null) out.add(err(p + ".points", "E_BEZIER_POINT_X", "控制点 x 必须是整数"));
                                if (intOrNull(pm.get("y")) == null) out.add(err(p + ".points", "E_BEZIER_POINT_Y", "控制点 y 必须是整数"));
                                if (intOrNull(pm.get("z")) == null) out.add(err(p + ".points", "E_BEZIER_POINT_Z", "控制点 z 必须是整数"));
                            }
                        }
                    }
                    if (count != 16) out.add(err(p + ".points", "E_BEZIER_POINTS_COUNT", "BEZIER_SURFACE 需要 16 个控制点（当前=" + count + "）"));
                }
                if (m.get("uSamples") != null || m.get("u") != null) requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("vSamples") != null || m.get("v") != null) requireIntMin(out, p, m, "vSamples", 2);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
                if (m.get("connectMaxStep") != null) requireIntMin(out, p, m, "connectMaxStep", 1);
            }
            if (op.equals("BEZIER_SURFACE_SET")) {
                Object patches = m.get("patches");
                if (!(patches instanceof List<?> pl) || pl.isEmpty()) {
                    out.add(err(p + ".patches", "E_BEZIER_SET_PATCHES", "BEZIER_SURFACE_SET.patches 必须是数组且非空"));
                } else {
                    for (int pi = 0; pi < pl.size(); pi++) {
                        Object it2 = pl.get(pi);
                        String pp = p + ".patches[" + pi + "]";
                        if (!(it2 instanceof Map<?, ?> pm)) {
                            out.add(err(pp, "E_BEZIER_SET_PATCH_NOT_OBJECT", "patch 条目必须是对象（map）"));
                            continue;
                        }
                        warnUnknownKeys(out, pp, pm, java.util.Set.of(
                                "id",
                                "at", "x", "y", "z",
                                "points",
                                "uSamples", "vSamples", "u", "v",
                                "thickness",
                                "material",
                                "connectSamples"
                        ));
                        // points must be 16 points or 4x4 grid
                        Object pts = pm.get("points");
                        if (!(pts instanceof List<?>)) out.add(err(pp + ".points", "E_BEZIER_POINTS_MISSING", "patch.points 必须是数组（16 点或 4x4 网格）"));
                        // at
                        Object at = pm.get("at");
                        if (at != null && !(at instanceof Map<?, ?>)) out.add(err(pp + ".at", "E_BEZIER_SET_AT_TYPE", "at 必须是对象（map）"));
                        // optional ints
                        if (pm.get("uSamples") != null || pm.get("u") != null) requireIntMin(out, pp, pm, "uSamples", 2);
                        if (pm.get("vSamples") != null || pm.get("v") != null) requireIntMin(out, pp, pm, "vSamples", 2);
                        if (pm.get("thickness") != null) requireIntMin(out, pp, pm, "thickness", 1);
                        if (pm.get("connectSamples") != null && !(pm.get("connectSamples") instanceof Boolean)) {
                            out.add(err(pp + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                        }
                    }
                }
                if (m.get("uSamples") != null || m.get("u") != null) requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("vSamples") != null || m.get("v") != null) requireIntMin(out, p, m, "vSamples", 2);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
                if (m.get("stitch") != null && !(m.get("stitch") instanceof Boolean)) {
                    out.add(err(p + ".stitch", "E_BOOL_TYPE", "stitch 必须是布尔"));
                }
                if (m.get("stitchEpsilon") != null || m.get("stitch_eps") != null) requireIntMin(out, p, m, "stitchEpsilon", 0);
                if (m.get("stitchSamples") != null || m.get("stitch_samples") != null) requireIntMin(out, p, m, "stitchSamples", 2);
                if (m.get("stitchResampleMode") != null || m.get("stitch_resample_mode") != null) {
                    String mode = str(m.get("stitchResampleMode"), str(m.get("stitch_resample_mode"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!mode.isEmpty() && !(mode.equals("RESAMPLE") || mode.equals("NONE") || mode.equals("AUTO"))) {
                        out.add(warn(p + ".stitchResampleMode", "W_BEZIER_SET_RESAMPLE_MODE", "stitchResampleMode 建议 RESAMPLE/NONE/AUTO（当前=" + mode + "）"));
                    }
                }
                if (m.get("capWidth") != null || m.get("cap_width") != null) requireIntMin(out, p, m, "capWidth", 0);
                // topology.grid or legacy grid
                Object grid = m.get("grid");
                Object topo = m.get("topology");
                if (topo instanceof Map<?, ?> tm && tm.get("grid") != null) grid = tm.get("grid");
                if (grid != null && !(grid instanceof List<?>)) {
                    out.add(err(p + ".topology.grid", "E_BEZIER_SET_GRID_TYPE", "grid 必须是二维数组"));
                }
                if (topo instanceof Map<?, ?> tm2 && tm2.get("links") != null && !(tm2.get("links") instanceof List<?>)) {
                    out.add(err(p + ".topology.links", "E_BEZIER_SET_LINKS_TYPE", "topology.links 必须是数组"));
                }
                if (topo instanceof Map<?, ?> tm3 && tm3.get("links") instanceof List<?> links) {
                    for (int li = 0; li < links.size(); li++) {
                        Object lo = links.get(li);
                        String lp = p + ".topology.links[" + li + "]";
                        if (!(lo instanceof Map<?, ?> lm)) {
                            out.add(err(lp, "E_BEZIER_SET_LINK_NOT_OBJECT", "links[] 条目必须是对象（map）"));
                            continue;
                        }
                        warnUnknownKeys(out, lp, lm, java.util.Set.of(
                                "a", "b", "ea", "eb",
                                "from", "to", "fromEdge", "toEdge",
                                "edgeA", "edgeB",
                                "aRange", "a_range", "fromRange",
                                "bRange", "b_range", "toRange",
                                "epsilon", "stitchEpsilon",
                                "samples", "stitchSamples",
                                "resampleMode", "stitchResampleMode",
                                "thickness",
                                "capWidth", "cap_width",
                                "capMaterial", "cap_material"
                        ));
                        if (lm.get("a") == null && lm.get("from") == null) out.add(err(lp + ".a", "E_BEZIER_SET_LINK_A", "link.a/from 缺失"));
                        if (lm.get("b") == null && lm.get("to") == null) out.add(err(lp + ".b", "E_BEZIER_SET_LINK_B", "link.b/to 缺失"));
                        Object ea = lm.get("ea");
                        if (ea == null) ea = lm.get("edgeA");
                        if (ea == null) ea = lm.get("fromEdge");
                        Object eb = lm.get("eb");
                        if (eb == null) eb = lm.get("edgeB");
                        if (eb == null) eb = lm.get("toEdge");
                        if (ea == null) out.add(err(lp + ".ea", "E_BEZIER_SET_LINK_EA", "link.ea 缺失"));
                        if (eb == null) out.add(err(lp + ".eb", "E_BEZIER_SET_LINK_EB", "link.eb 缺失"));
                        if (lm.get("epsilon") != null || lm.get("stitchEpsilon") != null) requireIntMin(out, lp, lm, "epsilon", 0);
                        if (lm.get("samples") != null || lm.get("stitchSamples") != null) requireIntMin(out, lp, lm, "samples", 2);
                        if (lm.get("thickness") != null) requireIntMin(out, lp, lm, "thickness", 1);
                        if (lm.get("capWidth") != null || lm.get("cap_width") != null) requireIntMin(out, lp, lm, "capWidth", 0);

                        validateRange01(out, lp + ".aRange", lm.get("aRange"), lm.get("a_range"), lm.get("fromRange"));
                        validateRange01(out, lp + ".bRange", lm.get("bRange"), lm.get("b_range"), lm.get("toRange"));
                    }
                }
            }
            if (op.equals("SURFACE_OFFSET")) {
                if (!(m.get("source") instanceof Map<?, ?>)) {
                    out.add(err(p + ".source", "E_SURF_OFFSET_SOURCE", "SURFACE_OFFSET.source 必须是对象（map）"));
                }
                if (m.get("uSamples") != null || m.get("u") != null) requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("vSamples") != null || m.get("v") != null) requireIntMin(out, p, m, "vSamples", 2);
                if (m.get("offset") != null || m.get("distance") != null) requireIntIfPresent(out, p, m, "offset");
                if (m.get("shellThickness") != null || m.get("shell_thickness") != null) requireIntMin(out, p, m, "shellThickness", 1);
                if (m.get("normalMode") != null || m.get("normal_mode") != null) {
                    String nm = str(m.get("normalMode"), str(m.get("normal_mode"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!nm.isEmpty() && !(nm.equals("DDA") || nm.equals("AXIS"))) {
                        out.add(warn(p + ".normalMode", "W_SURF_OFFSET_NORMAL_MODE", "normalMode 建议 DDA/AXIS（当前=" + nm + "）"));
                    }
                }
                if (m.get("stepLen") != null || m.get("step_len") != null || m.get("step") != null) {
                    Double sl = doubleOrNull(m.get("stepLen"));
                    if (sl == null) sl = doubleOrNull(m.get("step_len"));
                    if (sl == null) sl = doubleOrNull(m.get("step"));
                    if (sl == null) out.add(err(p + ".stepLen", "E_DOUBLE", "stepLen/step 必须是数字"));
                    else if (sl < 0.25 || sl > 4.0) out.add(warn(p + ".stepLen", "W_RANGE", "stepLen 建议在 0.25..4.0（当前=" + sl + "）"));
                }
                if (m.get("connectMaxStep") != null || m.get("connect_max_step") != null) requireIntMin(out, p, m, "connectMaxStep", 1);
            }
            if (op.equals("IMPLICIT_FIELD")) {
                // bounds or w/d/h
                // (engine is permissive; validator keeps training stable)
                if (m.get("iso") != null && doubleOrNull(m.get("iso")) == null) out.add(err(p + ".iso", "E_DOUBLE_TYPE", "iso 必须是数字"));
                if (m.get("band") != null && doubleOrNull(m.get("band")) == null) out.add(err(p + ".band", "E_DOUBLE_TYPE", "band 必须是数字"));
            }
            if (op.equals("MARCHING_CUBES")) {
                if (m.get("iso") != null && doubleOrNull(m.get("iso")) == null) out.add(err(p + ".iso", "E_DOUBLE_TYPE", "iso 必须是数字"));
                if (m.get("fill") != null || m.get("samples") != null) requireIntMin(out, p, m, "fill", 1);
            }
            if (op.equals("REVOLVE_SURFACE")) {
                Object profObj = m.get("profileRings");
                if (profObj == null) profObj = m.get("rings");
                if (profObj == null) profObj = m.get("profilePoints");
                if (profObj == null) profObj = m.get("points");
                if (!(profObj instanceof List<?>)) {
                    out.add(err(p, "E_REVOLVE_PROFILE_MISSING", "REVOLVE_SURFACE 需要 profilePoints/profileRings（数组）"));
                }
                if (m.get("segments") != null) requireIntMin(out, p, m, "segments", 8);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
                if (m.get("angleDeg") != null || m.get("angle") != null) {
                    Double av = doubleOrNull(m.get("angleDeg"));
                    if (av == null) av = doubleOrNull(m.get("angle"));
                    if (av == null) out.add(err(p + ".angleDeg", "E_DOUBLE_TYPE", "angleDeg/angle 必须是数字"));
                }
            }
            if (op.equals("LOFT_SURFACE")) {
                Object sec = m.get("sections");
                if (!(sec instanceof List<?> sl) || sl.size() < 2) {
                    out.add(err(p + ".sections", "E_LOFT_SECTIONS", "LOFT_SURFACE.sections 必须是数组且至少 2 段"));
                } else {
                    for (int si = 0; si < sl.size(); si++) {
                        Object it2 = sl.get(si);
                        String sp = p + ".sections[" + si + "]";
                        if (!(it2 instanceof Map<?, ?> sm)) {
                            out.add(err(sp, "E_LOFT_SECTION_NOT_OBJECT", "sections[] 条目必须是对象（map）"));
                            continue;
                        }
                        warnUnknownKeys(out, sp, sm, java.util.Set.of(
                                "at", "x", "y", "z",
                                "profilePoints", "profileRings", "rings"
                        ));
                        Object at = sm.get("at");
                        if (at != null && !(at instanceof Map<?, ?>)) out.add(err(sp + ".at", "E_LOFT_AT_TYPE", "at 必须是对象（map）"));
                        Object prof = sm.get("profileRings");
                        if (prof == null) prof = sm.get("rings");
                        if (prof == null) prof = sm.get("profilePoints");
                        if (!(prof instanceof List<?>)) out.add(err(sp, "E_LOFT_PROFILE_MISSING", "每段需要 profilePoints/profileRings（数组）"));
                    }
                }
                if (m.get("uSamples") != null || m.get("u") != null) requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
            }
            if (op.equals("ANCHOR_FOOTPRINT")) {
                if (m.get("x0") == null || m.get("x1") == null || m.get("z0") == null || m.get("z1") == null) {
                    out.add(err(p, "E_ANCHOR_FOOTPRINT_BOUNDS", "ANCHOR_FOOTPRINT 需要 x0/x1/z0/z1"));
                }
                if (m.get("yBase") != null && intOrNull(m.get("yBase")) == null) {
                    out.add(err(p + ".yBase", "E_INT_TYPE", "yBase 必须是整数"));
                }
                if (m.get("maxDepth") != null || m.get("anchorDepth") != null) requireIntMin(out, p, m, "maxDepth", 0);
            }
            if (op.equals("ANCHORAGE")) {
                if (m.get("w") != null || m.get("width") != null) requireIntMin(out, p, m, "w", 1);
                if (m.get("d") != null || m.get("depth") != null) requireIntMin(out, p, m, "d", 1);
                if (m.get("h") != null || m.get("height") != null) requireIntMin(out, p, m, "h", 1);
                if (m.get("yBase") != null && intOrNull(m.get("yBase")) == null) {
                    out.add(err(p + ".yBase", "E_INT_TYPE", "yBase 必须是整数"));
                }
                if (m.get("maxDepth") != null || m.get("anchorDepth") != null) requireIntMin(out, p, m, "maxDepth", 0);
                if (m.get("topBevel") != null || m.get("top_bevel") != null || m.get("bevel") != null) requireIntMin(out, p, m, "topBevel", 0);
                if (m.get("guardWallHeight") != null || m.get("guard_wall_height") != null || m.get("parapetHeight") != null) requireIntMin(out, p, m, "guardWallHeight", 0);
                if (m.get("guardWallInset") != null || m.get("guard_wall_inset") != null) requireIntMin(out, p, m, "guardWallInset", 0);
                Object holes = m.get("holes");
                if (holes == null) holes = m.get("cableHoles");
                if (holes != null && !(holes instanceof List<?>)) {
                    out.add(err(p + ".holes", "E_HOLES_TYPE", "holes/cableHoles 必须是数组"));
                }
                if (holes instanceof List<?> list) {
                    for (int hi = 0; hi < list.size(); hi++) {
                        Object holeIt = list.get(hi);
                        String hp = p + ".holes[" + hi + "]";
                        if (!(holeIt instanceof Map<?, ?> hm)) {
                            out.add(err(hp, "E_HOLE_NOT_OBJECT", "hole 必须是对象"));
                            continue;
                        }
                        Object face = hm.get("face");
                        if (face != null) {
                            String s = String.valueOf(face).trim().toUpperCase(Locale.ROOT);
                            if (!(s.equals("NORTH") || s.equals("SOUTH") || s.equals("EAST") || s.equals("WEST"))) {
                                out.add(warn(hp + ".face", "W_HOLE_FACE_VALUE", "hole.face 建议使用 NORTH/SOUTH/EAST/WEST（当前=" + face + "）"));
                            }
                        } else {
                            out.add(warn(hp + ".face", "W_HOLE_FACE_MISSING", "hole 建议提供 face（NORTH/SOUTH/EAST/WEST）"));
                        }
                        if (hm.get("r") != null || hm.get("radius") != null) requireIntMin(out, hp, hm, "r", 1);
                        if (hm.get("len") != null || hm.get("length") != null) requireIntMin(out, hp, hm, "len", 1);
                        if (hm.get("y") != null && intOrNull(hm.get("y")) == null) out.add(err(hp + ".y", "E_INT_TYPE", "hole.y 必须是整数"));
                        if (hm.get("x") != null && intOrNull(hm.get("x")) == null) out.add(err(hp + ".x", "E_INT_TYPE", "hole.x 必须是整数"));
                        if (hm.get("z") != null && intOrNull(hm.get("z")) == null) out.add(err(hp + ".z", "E_INT_TYPE", "hole.z 必须是整数"));
                    }
                }
            }
            if (op.equals("FRAME_GRID_3D")) {
                // bounds required
                if (m.get("x0") == null || m.get("x1") == null || m.get("y0") == null || m.get("y1") == null || m.get("z0") == null || m.get("z1") == null) {
                    out.add(err(p, "E_FRAMEGRID_BOUNDS", "FRAME_GRID_3D 需要 x0/x1/y0/y1/z0/z1"));
                }
                if (m.get("stepX") != null) requireIntMin(out, p, m, "stepX", 1);
                if (m.get("stepY") != null) requireIntMin(out, p, m, "stepY", 1);
                if (m.get("stepZ") != null) requireIntMin(out, p, m, "stepZ", 1);
                if (m.get("step") != null) requireIntMin(out, p, m, "step", 1);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                if (m.get("mode") != null) {
                    String s = String.valueOf(m.get("mode")).trim().toUpperCase(Locale.ROOT);
                    if (!s.isEmpty() && !(s.equals("SURFACE") || s.equals("ALL"))) {
                        out.add(warn(p + ".mode", "W_FRAMEGRID_MODE_VALUE", "mode 建议使用 SURFACE/ALL（当前=" + m.get("mode") + "）"));
                    }
                }
                if (m.get("diagonal") != null) {
                    String s = String.valueOf(m.get("diagonal")).trim().toUpperCase(Locale.ROOT);
                    if (!s.isEmpty() && !(s.equals("NONE") || s.equals("FACE") || s.equals("SPACE"))) {
                        out.add(warn(p + ".diagonal", "W_FRAMEGRID_DIAGONAL_VALUE", "diagonal 建议使用 NONE/FACE/SPACE（当前=" + m.get("diagonal") + "）"));
                    }
                }
            }
            if (op.equals("STAIR_SYSTEM")) {
                validatePointRefXYZ(out, p + ".from", m.get("from"), "E_STAIR_FROM_MISSING");
                validatePointRefXYZ(out, p + ".to", m.get("to"), "E_STAIR_TO_MISSING");
                if (m.get("width") != null) requireIntMin(out, p, m, "width", 1);
                if (m.get("clearHeight") != null || m.get("clear_h") != null) requireIntMin(out, p, m, "clearHeight", 0);
            }
            if (op.equals("PATH_ROUTE") || op.equals("WALL_ROUTE") || op.equals("BRIDGE_ROUTE")) {
                validatePointRefXYZ(out, p + ".from", m.get("from"), "E_ROUTE_FROM_MISSING");
                validatePointRefXYZ(out, p + ".to", m.get("to"), "E_ROUTE_TO_MISSING");
                if (m.get("terrainAdaptation") != null) {
                    validateTerrainAdaptation(out, p + ".terrainAdaptation", m.get("terrainAdaptation"), op);
                }
                if (op.equals("PATH_ROUTE")) {
                    if (m.get("width") != null || m.get("thickness") != null) requireIntMin(out, p, m, "width", 1);
                }
                if (op.equals("WALL_ROUTE")) {
                    if (m.get("wallHeight") != null || m.get("height") != null) requireIntMin(out, p, m, "wallHeight", 1);
                    if (m.get("wallThickness") != null || m.get("thickness") != null) requireIntMin(out, p, m, "wallThickness", 1);
                    if (m.get("foundationDepth") != null) requireIntMin(out, p, m, "foundationDepth", 0);
                    if (m.get("maxStep") != null) requireIntMin(out, p, m, "maxStep", 0);
                }
                if (op.equals("BRIDGE_ROUTE")) {
                    if (m.get("width") != null) requireIntMin(out, p, m, "width", 1);
                }
            }
            if (op.equals("EXTRUDE_POLYGON")) {
                if (m.get("h") != null || m.get("height") != null) requireIntMin(out, p, m, "h", 1);
                if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
                String shape = str(m.get("shape"), "RECT").trim().toUpperCase(Locale.ROOT);
                if (!(shape.equals("RECT") || shape.equals("POINTS") || shape.equals("POLYGON"))) {
                    // Engine treats non-RECT as points; still warn loudly for training
                    out.add(warn(p + ".shape", "W_SHAPE_VALUE", "EXTRUDE_POLYGON.shape 建议使用 RECT 或 points[]"));
                }
                if (shape.equals("RECT")) {
                    requireIntMin(out, p, m, "w", 1);
                    requireIntMin(out, p, m, "d", 1);
                } else {
                    Object pts = m.get("points");
                    if (!(pts instanceof List<?> l) || l.size() < 3) {
                        out.add(err(p + ".points", "E_EXTRUDE_POINTS_MIN3", "EXTRUDE_POLYGON.points 至少需要 3 个点（每个点需含 x/z）"));
                    } else {
                        validatePointsXZ(out, p + ".points", l);
                    }
                }
            }
            if (op.equals("ROOF_COVER")) {
                String type = str(m.get("type"), "FLAT").trim().toUpperCase(Locale.ROOT);
                if (!(type.equals("FLAT") || type.equals("GABLE"))) {
                    out.add(err(p + ".type", "E_ROOF_TYPE_VALUE", "ROOF_COVER.type 取值非法（允许 FLAT/GABLE）: " + type));
                }
                if (m.get("w") != null) requireIntMin(out, p, m, "w", 1);
                if (m.get("d") != null) requireIntMin(out, p, m, "d", 1);
                if (m.get("y") != null && intOrNull(m.get("y")) == null) out.add(err(p + ".y", "E_INT_TYPE", "y 必须是整数"));
                if (m.get("overhang") != null) requireIntMin(out, p, m, "overhang", 0);
                if (m.get("rise") != null) requireIntMin(out, p, m, "rise", 1);
            }
            if (op.equals("BSP_FLOOR_PLAN")) {
                requireIntMin(out, p, m, "w", 1);
                requireIntMin(out, p, m, "d", 1);
                requireIntMin(out, p, m, "h", 1);
                Object cfg = m.get("floor_plan_logic");
                if (cfg == null) cfg = m.get("config");
                if (cfg == null) cfg = m.get("floorPlanLogic");
                if (cfg == null) {
                    out.add(err(p + ".config", "E_BSP_CONFIG_MISSING", "BSP_FLOOR_PLAN 需要 config/floor_plan_logic"));
                } else if (!(cfg instanceof Map<?, ?>)) {
                    out.add(err(p + ".config", "E_BSP_CONFIG_TYPE", "BSP_FLOOR_PLAN.config 必须是对象（map）"));
                }
            }

            // P0 per-op required fields
            if (op.contains("SPLINE")) {
                Object pts = m.get("points");
                if (!(pts instanceof List<?> l) || l.size() < 2) {
                    out.add(err(p + ".points", "E_SPLINE_POINTS_MIN2", "SPLINE_SWEEP.points 至少需要 2 个点"));
                } else {
                    validatePoints(out, p + ".points", l);
                }
                // profile enum sanity (optional but training helpful)
                String profile = str(m.get("profile"), "SPHERE").trim().toUpperCase(Locale.ROOT);
                boolean ok = profile.equals("SPHERE") || profile.equals("RECT") || profile.equals("POLYGON");
                if (!ok) out.add(err(p + ".profile", "E_SPLINE_PROFILE_VALUE", "SPLINE_SWEEP.profile 取值非法（SPHERE/RECT/POLYGON）: " + profile));
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

    private static void validateMacro(List<AssemblyValidationIssue> out, Map<?, ?> macro) {
        warnUnknownKeys(out, "$.macro", macro, Set.of(
                "primaryComponent", "primaryComponentId",
                "shapeType", "shape",
                "heightScale", "height_scale",
                "roofType", "roof_type",
                "roofCurvature", "roof_curvature",
                "overhang",
                "openness",
                "symmetry",
                "bridgeTower", "bridge_tower",
                "style", "culture"
        ));

        Object st = macro.get("shapeType");
        if (st == null) st = macro.get("shape");
        if (st != null) {
            String s = String.valueOf(st).trim().toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) {
                boolean ok = s.contains("RECT") || s.contains("BOX") || s.contains("CIRC") || s.contains("CYL") || s.contains("HEX");
                if (!ok) out.add(warn("$.macro" + ".shapeType", "W_MACRO_SHAPE_VALUE", "shapeType 可能不可识别: " + st));
            }
        }

        Object hs = macro.get("heightScale");
        if (hs == null) hs = macro.get("height_scale");
        if (hs != null) {
            if (!(hs instanceof Number) && !(hs instanceof String)) {
                out.add(err("$.macro" + ".heightScale", "E_MACRO_HEIGHTSCALE_TYPE", "heightScale 必须是数字或枚举字符串"));
            }
        }

        Object op = macro.get("openness");
        if (op != null) {
            Double d = doubleOrNull(op);
            if (d == null) out.add(err("$.macro" + ".openness", "E_MACRO_OPENNESS_TYPE", "openness 必须是 0..1 的数字"));
            else if (d < 0.0 || d > 1.0) out.add(warn("$.macro" + ".openness", "W_MACRO_OPENNESS_RANGE", "openness 建议范围 0..1（当前=" + d + "）"));
        }

        Object rt = macro.get("roofType");
        if (rt == null) rt = macro.get("roof_type");
        if (rt != null) {
            String s = String.valueOf(rt).trim().toUpperCase(Locale.ROOT);
            if (!s.isEmpty() && !(s.equals("FLAT") || s.equals("GABLE"))) {
                out.add(warn("$.macro" + ".roofType", "W_MACRO_ROOFTYPE_VALUE", "roofType 当前仅支持 FLAT/GABLE（当前=" + rt + "）"));
            }
        }

        Object bt = macro.get("bridgeTower");
        if (bt == null) bt = macro.get("bridge_tower");
        if (bt != null) {
            if (!(bt instanceof Map<?, ?>) && !(bt instanceof Boolean)) {
                out.add(warn("$.macro" + ".bridgeTower", "W_MACRO_BRIDGETOWER_TYPE", "bridgeTower 建议是对象（map）或 true（启用默认注入）"));
            }
        }

        Object styleObj = macro.get("style");
        if (styleObj == null) styleObj = macro.get("culture");
        if (styleObj != null) {
            if (!(styleObj instanceof Map<?, ?> m)) {
                out.add(warn("$.macro" + ".style", "W_MACRO_STYLE_TYPE", "style/culture 建议是对象（map）"));
            } else {
                warnUnknownKeys(out, "$.macro" + ".style", m, Set.of(
                        "styleId", "style_id", "id",
                        "intent", "mood",
                        "density", "symmetry", "verticality", "transparency",
                        "structureExposure", "structure_exposure"
                ));
                // numeric sliders: 0..1 recommended
                for (String k : new String[]{"density","symmetry","verticality","transparency","structureExposure","structure_exposure"}) {
                    Object v = m.get(k);
                    if (v == null) continue;
                    Double d = doubleOrNull(v);
                    if (d == null) out.add(err("$.macro" + ".style." + k, "E_MACRO_STYLE_SLIDER_TYPE", k + " 必须是数字"));
                    else if (d < 0.0 || d > 1.0) out.add(warn("$.macro" + ".style." + k, "W_MACRO_STYLE_SLIDER_RANGE", k + " 建议范围 0..1（当前=" + d + "）"));
                }
            }
        }
    }

    private static Double doubleOrNull(Object v) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return null;
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
                    // truss
                    "from", "to",
                    "pattern", "module", "step",
                    "chord", "web", "joint",
                    // arch
                    "rise", "sagitta", "samples", "steps",
                    // buttress
                    "rib", "pier", "pierDown", "pier_down",
                    // cable
                    "sag", "droop",
                    "hangersEvery", "hangerEvery",
                    "hangersToY", "hangerToY",
                    "hangersMaterial",
                    "cableCount", "count",
                    "cableSpacing", "spacing",
                    "cableAxis",
                    // frame grid 3d
                    "stepX", "stepY", "stepZ",
                    "sx", "sy", "sz",
                    "mode",
                    "diagonal",
                    // stair system
                    "clearHeight", "clear_h",
                    "support",
                    "stairs",
                    "supportMaterial",
                    // anchoring / anchorage
                    "x0", "x1", "y0", "y1", "z0", "z1",
                    "yBase",
                    "maxDepth", "anchorDepth",
                    "stopOnSolid",
                    "allowWaterEdit", "allowLavaEdit",
                    "solid",
                    "carve",
                    // anchorage detailing
                    "topBevel", "top_bevel", "bevel",
                    "guardWallHeight", "guard_wall_height", "parapetHeight",
                    "guardWallInset", "guard_wall_inset",
                    "guardWallCrenels", "guard_wall_crenels", "crenels",
                    "guardWallMaterial", "guardWall",
                    "holes", "cableHoles",
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
            if (type.contains("CYLINDER")) {
                // allow defaults but validate if provided
                if (c.get("r") != null || c.get("radius") != null) {
                    Integer rv = intOrNull(c.get("r"));
                    if (rv == null) rv = intOrNull(c.get("radius"));
                    if (rv == null) out.add(err(p + ".r", "E_INT_TYPE", "r/radius 必须是整数"));
                    else if (rv < 2) out.add(err(p + ".r", "E_INT_RANGE", "r/radius 必须 >= 2"));
                }
                if (c.get("h") != null || c.get("height") != null) {
                    Integer hv = intOrNull(c.get("h"));
                    if (hv == null) hv = intOrNull(c.get("height"));
                    if (hv == null) out.add(err(p + ".h", "E_INT_TYPE", "h/height 必须是整数"));
                    else if (hv < 1) out.add(err(p + ".h", "E_INT_RANGE", "h/height 必须 >= 1"));
                }
                if (c.get("thickness") != null) requireIntMin(out, p, c, "thickness", 1);
            }
            if (type.contains("EXTRUDE")) {
                if (c.get("h") != null || c.get("height") != null) requireIntMin(out, p, c, "h", 1);
                if (c.get("thickness") != null) requireIntMin(out, p, c, "thickness", 1);
                String shape = str(c.get("shape"), "RECT").trim().toUpperCase(Locale.ROOT);
                if ("RECT".equals(shape)) {
                    if (c.get("w") != null) requireIntMin(out, p, c, "w", 1);
                    if (c.get("d") != null) requireIntMin(out, p, c, "d", 1);
                } else {
                    Object pts = c.get("points");
                    if (pts != null && (!(pts instanceof List<?> l) || l.size() < 3)) {
                        out.add(err(p + ".points", "E_EXTRUDE_POINTS_MIN3", "EXTRUDE_POLYGON.points 至少需要 3 个点（每个点需含 x/z）"));
                    }
                }
            }
            if (type.contains("ROOF")) {
                Object rt = c.get("roofType");
                if (rt == null) rt = c.get("kind");
                if (rt != null) {
                    String t = String.valueOf(rt).trim().toUpperCase(Locale.ROOT);
                    if (!(t.equals("FLAT") || t.equals("GABLE"))) {
                        out.add(err(p + ".roofType", "E_ROOF_TYPE_VALUE", "ROOF_COVER.type 取值非法（允许 FLAT/GABLE）: " + t));
                    }
                }
            }
            if (type.contains("BSP")) {
                Object cfg = c.get("config");
                if (cfg == null) cfg = c.get("floor_plan_logic");
                if (cfg == null) cfg = c.get("floorPlanLogic");
                if (cfg == null) {
                    out.add(err(p + ".config", "E_BSP_CONFIG_MISSING", "BSP_FLOOR_PLAN 需要 config/floor_plan_logic"));
                }
            }
        }
    }

    private static void validateFacadeMacros(List<AssemblyValidationIssue> out, String compPath, Map<?, ?> comp) {
        Object facadeObj = comp.get("facade");
        if (facadeObj == null) facadeObj = comp.get("facade_logic");
        if (facadeObj == null) facadeObj = comp.get("facadeLogic");
        if (!(facadeObj instanceof Map<?, ?> facade)) return;
        warnUnknownKeys(out, compPath + ".facade", facade, java.util.Set.of(
                "surfacePattern", "pattern",
                "openings", "opening",
                "facadeGrid", "FACADE_GRID", "curtainWall",
                "surfaceBands", "SURFACE_BANDS", "bands",
                "face", "faces" // allow user to mistakenly place here; we'll still warn unknown others
        ));

        // facadeGrid
        Object fg = facade.get("facadeGrid");
        if (fg == null) fg = facade.get("FACADE_GRID");
        if (fg == null) fg = facade.get("curtainWall");
        if (fg instanceof Map<?, ?> m) {
            String p = compPath + ".facade.facadeGrid";
            warnUnknownKeys(out, p, m, java.util.Set.of(
                    "face", "faces",
                    "bayW", "bayH", "moduleW", "moduleH", "gridW", "gridH",
                    "mullionThickness", "mullionT",
                    "transomThickness", "transomT",
                    "borderThickness", "borderT",
                    "marginU", "marginX", "marginY",
                    "inset", "depth",
                    "frame", "fill", "material",
                    "spandrelEvery", "spEvery",
                    "spandrelHeight", "spH",
                    "spandrelOffset", "spOffset",
                    "spandrelFill"
            ));
            // faces optional; default ALL, but validate if present
            if (m.get("face") != null || m.get("faces") != null) requireFacesString(out, p, m);
            requireIntMin(out, p, m, "bayW", 1);
            requireIntMin(out, p, m, "bayH", 1);
            // spandrel optional
            if (m.get("spandrelEvery") != null || m.get("spEvery") != null) requireIntMin(out, p, m, "spandrelEvery", 0);
            if (m.get("spandrelHeight") != null || m.get("spH") != null) requireIntMin(out, p, m, "spandrelHeight", 0);
        }

        // surfacePattern
        Object sp = facade.get("surfacePattern");
        if (sp == null) sp = facade.get("pattern");
        if (sp instanceof Map<?, ?> m) {
            String p = compPath + ".facade.surfacePattern";
            warnUnknownKeys(out, p, m, java.util.Set.of(
                    "face", "faces",
                    "type", "pattern",
                    "step", "spacing", "thickness",
                    "material", "accent", "frame"
            ));
            if (m.get("face") != null || m.get("faces") != null) requireFacesString(out, p, m);
            String pattern = str(m.get("pattern"), str(m.get("type"), "GRID")).trim().toUpperCase(Locale.ROOT);
            if (!(pattern.equals("GRID") || pattern.equals("STRIPES_V") || pattern.equals("STRIPES_H") || pattern.equals("RIBS_V") || pattern.equals("RIBS_H")
                    || pattern.equals("STRIPES_VERTICAL") || pattern.equals("STRIPES_HORIZONTAL") || pattern.equals("RIBS_VERTICAL") || pattern.equals("RIBS_HORIZONTAL"))) {
                out.add(err(p + ".pattern", "E_PATTERN_VALUE", "surfacePattern.pattern 取值非法: " + pattern));
            }
            // step/thickness can be omitted; only validate if present
            if (m.get("step") != null || m.get("spacing") != null) requireIntMin(out, p, m, "step", 1);
            if (m.get("thickness") != null) requireIntMin(out, p, m, "thickness", 1);
        }

        // surfaceBands
        Object sb = facade.get("surfaceBands");
        if (sb == null) sb = facade.get("SURFACE_BANDS");
        if (sb == null) sb = facade.get("bands");
        if (sb instanceof Map<?, ?> m) {
            String p = compPath + ".facade.surfaceBands";
            warnUnknownKeys(out, p, m, java.util.Set.of(
                    "face", "faces",
                    "horizontalBands", "hBands", "bandsH",
                    "verticalBands", "vBands", "bandsV"
            ));
            if (m.get("face") != null || m.get("faces") != null) requireFacesString(out, p, m);
            Object hb = m.get("horizontalBands");
            if (hb == null) hb = m.get("hBands");
            if (hb == null) hb = m.get("bandsH");
            if (hb != null && !(hb instanceof List<?>)) out.add(err(p + ".horizontalBands", "E_BANDS_H_NOT_LIST", "horizontalBands 必须是数组"));

            Object vb = m.get("verticalBands");
            if (vb == null) vb = m.get("vBands");
            if (vb == null) vb = m.get("bandsV");
            if (vb != null && !(vb instanceof List<?>)) out.add(err(p + ".verticalBands", "E_BANDS_V_NOT_LIST", "verticalBands 必须是数组"));

            // Validate band entries (training stability)
            if (hb instanceof List<?> hList) {
                for (int i = 0; i < hList.size(); i++) {
                    Object it = hList.get(i);
                    String bp = p + ".horizontalBands[" + i + "]";
                    if (!(it instanceof Map<?, ?> bm)) {
                        out.add(err(bp, "E_BAND_H_NOT_OBJECT", "horizontalBands 条目必须是对象（map）"));
                        continue;
                    }
                    warnUnknownKeys(out, bp, bm, java.util.Set.of(
                            "y", "atY",
                            "height", "h",
                            "material",
                            "inset",
                            "outset", "out",
                            "depth"
                    ));
                    // required y
                    Object yv = bm.get("y");
                    if (yv == null) yv = bm.get("atY");
                    if (yv == null) out.add(err(bp + ".y", "E_BAND_H_Y_MISSING", "horizontalBands[].y 缺失"));
                    else if (intOrNull(yv) == null) out.add(err(bp + ".y", "E_INT_TYPE", "y 必须是整数"));

                    // optional ints with ranges
                    if (bm.get("height") != null || bm.get("h") != null) requireIntMin(out, bp, bm, "height", 1);
                    if (bm.get("inset") != null) requireIntMin(out, bp, bm, "inset", 0);
                    if (bm.get("outset") != null || bm.get("out") != null) {
                        Object ov = bm.get("outset");
                        if (ov == null) ov = bm.get("out");
                        if (intOrNull(ov) == null) out.add(err(bp + ".outset", "E_INT_TYPE", "outset 必须是整数"));
                        else if (intOrNull(ov) < 0) out.add(err(bp + ".outset", "E_INT_RANGE", "outset 必须 >= 0"));
                    }
                    if (bm.get("depth") != null) requireIntMin(out, bp, bm, "depth", 1);
                }
            }

            if (vb instanceof List<?> vList) {
                for (int i = 0; i < vList.size(); i++) {
                    Object it = vList.get(i);
                    String bp = p + ".verticalBands[" + i + "]";
                    if (!(it instanceof Map<?, ?> bm)) {
                        out.add(err(bp, "E_BAND_V_NOT_OBJECT", "verticalBands 条目必须是对象（map）"));
                        continue;
                    }
                    warnUnknownKeys(out, bp, bm, java.util.Set.of(
                            "step", "spacing",
                            "width", "thickness",
                            "offset",
                            "y0", "y1",
                            "material",
                            "inset",
                            "outset", "out",
                            "depth"
                    ));
                    // required step/width
                    Object sv = bm.get("step");
                    if (sv == null) sv = bm.get("spacing");
                    if (sv == null) out.add(err(bp + ".step", "E_BAND_V_STEP_MISSING", "verticalBands[].step 缺失"));
                    else if (intOrNull(sv) == null) out.add(err(bp + ".step", "E_INT_TYPE", "step 必须是整数"));
                    else if (intOrNull(sv) < 1) out.add(err(bp + ".step", "E_INT_RANGE", "step 必须 >= 1"));

                    Object wv = bm.get("width");
                    if (wv == null) wv = bm.get("thickness");
                    if (wv == null) out.add(err(bp + ".width", "E_BAND_V_WIDTH_MISSING", "verticalBands[].width 缺失"));
                    else if (intOrNull(wv) == null) out.add(err(bp + ".width", "E_INT_TYPE", "width 必须是整数"));
                    else if (intOrNull(wv) < 1) out.add(err(bp + ".width", "E_INT_RANGE", "width 必须 >= 1"));

                    if (bm.get("offset") != null && intOrNull(bm.get("offset")) == null) {
                        out.add(err(bp + ".offset", "E_INT_TYPE", "offset 必须是整数"));
                    }
                    if (bm.get("y0") != null && intOrNull(bm.get("y0")) == null) out.add(err(bp + ".y0", "E_INT_TYPE", "y0 必须是整数"));
                    if (bm.get("y1") != null && intOrNull(bm.get("y1")) == null) out.add(err(bp + ".y1", "E_INT_TYPE", "y1 必须是整数"));
                    if (bm.get("inset") != null) requireIntMin(out, bp, bm, "inset", 0);
                    if (bm.get("outset") != null || bm.get("out") != null) {
                        Object ov = bm.get("outset");
                        if (ov == null) ov = bm.get("out");
                        if (intOrNull(ov) == null) out.add(err(bp + ".outset", "E_INT_TYPE", "outset 必须是整数"));
                        else if (intOrNull(ov) < 0) out.add(err(bp + ".outset", "E_INT_RANGE", "outset 必须 >= 0"));
                    }
                    if (bm.get("depth") != null) requireIntMin(out, bp, bm, "depth", 1);
                }
            }
        }

        // openings[] (facade-side macro form)
        Object openingsObj = facade.get("openings");
        if (openingsObj == null) openingsObj = facade.get("opening");
        if (openingsObj instanceof List<?> list) {
            String base = compPath + ".facade.openings";
            for (int i = 0; i < list.size(); i++) {
                Object it = list.get(i);
                String p = base + "[" + i + "]";
                if (!(it instanceof Map<?, ?> m)) {
                    out.add(err(p, "E_OPENING_NOT_OBJECT", "opening 条目必须是对象（map）"));
                    continue;
                }
                warnUnknownKeys(out, p, m, java.util.Set.of(
                        "face", "faces",
                        "kind", "type",
                        "rows", "cols",
                        "winW", "w", "winH", "h",
                        "sillY", "marginX", "marginY", "gapX", "gapY",
                        "frameThickness", "mullionStep",
                        "doorW", "doorH",
                        "archType", "arch", "archThickness", "archT",
                        "keystone", "keystoneOn",
                        "tracery", "traceryType", "traceryMaterial",
                        "traceryThickness", "traceryT",
                        "traceryY", "traceryInset",
                        "foilRadius", "foilCenterY", "foilCount", "foilStepY", "foilGapY",
                        "traceryFoilRadius", "traceryFoilCenterY", "traceryFoilCount", "traceryFoilStepY",
                        "r", "radius", "centerY",
                        "petals", "spokes", "ring", "phase", "phi",
                        "spokeWidth", "spokeW", "spokeThreshold", "spokeThresh",
                        "innerFill", "spokeMaterial",
                        "fill", "frame", "air"
                ));
                if (m.get("face") != null || m.get("faces") != null) requireFacesString(out, p, m);

                String kind = str(m.get("kind"), str(m.get("type"), "")).trim().toUpperCase(Locale.ROOT);
                if (kind.isBlank()) {
                    out.add(err(p + ".kind", "E_OPENINGS_KIND_MISSING", "openings[].kind 缺失"));
                } else {
                    boolean ok = kind.equals("WINDOW_GRID") || kind.equals("DOOR") || kind.equals("ARCH_WINDOW") || kind.equals("ROSE_WINDOW");
                    if (!ok) out.add(err(p + ".kind", "E_OPENINGS_KIND_VALUE", "openings[].kind 取值非法: " + kind));
                }

                // If provided, validate sizing knobs. (Engine has defaults; do not require them.)
                if (m.get("rows") != null) requireIntMin(out, p, m, "rows", 1);
                if (m.get("cols") != null) requireIntMin(out, p, m, "cols", 1);
                if (m.get("winW") != null || m.get("w") != null) requireIntMin(out, p, m, "winW", 1);
                if (m.get("winH") != null || m.get("h") != null) requireIntMin(out, p, m, "winH", 1);
                if (m.get("doorW") != null) requireIntMin(out, p, m, "doorW", 1);
                if (m.get("doorH") != null) requireIntMin(out, p, m, "doorH", 2);

                // Rose window sanity
                if (m.get("r") != null || m.get("radius") != null) {
                    Integer rv = intOrNull(m.get("r"));
                    if (rv == null) rv = intOrNull(m.get("radius"));
                    if (rv != null && rv < 2) out.add(err(p + ".r", "E_ROSE_R_RANGE", "ROSE_WINDOW.r 必须 >= 2"));
                }
                if (m.get("petals") != null || m.get("spokes") != null) {
                    Integer pv = intOrNull(m.get("petals"));
                    if (pv == null) pv = intOrNull(m.get("spokes"));
                    if (pv != null && pv < 3) out.add(err(p + ".petals", "E_ROSE_PETALS_RANGE", "ROSE_WINDOW.petals 必须 >= 3"));
                }
                // Arch type sanity
                if (m.get("archType") != null || m.get("arch") != null) {
                    String at = str(m.get("archType"), str(m.get("arch"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!at.isEmpty() && !(at.equals("ROUND") || at.equals("POINTED"))) {
                        out.add(err(p + ".archType", "E_ARCH_TYPE_VALUE", "ARCH_WINDOW.archType 取值非法: " + at + "（允许 ROUND/POINTED）"));
                    }
                }
            }
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
            if (c.get("terrainAdaptation") != null) {
                String type = str(c.get("type"), "CONNECTOR_LINE").trim().toUpperCase(Locale.ROOT);
                validateTerrainAdaptation(out, p + ".terrainAdaptation", c.get("terrainAdaptation"), "CONNECTION:" + type);
            }
        }
    }

    private static void validateRange01(List<AssemblyValidationIssue> out, String path, Object v0, Object v1, Object v2) {
        Object v = (v0 != null) ? v0 : (v1 != null ? v1 : v2);
        if (v == null) return;
        if (!(v instanceof List<?> list) || list.size() < 2) {
            out.add(err(path, "E_RANGE_TYPE", "range 必须是数组 [t0,t1]"));
            return;
        }
        Double a = doubleOrNull(list.get(0));
        Double b = doubleOrNull(list.get(1));
        if (a == null || b == null) {
            out.add(err(path, "E_RANGE_NUM", "range 必须是数字 [t0,t1]"));
            return;
        }
        if (a < 0.0 || a > 1.0 || b < 0.0 || b > 1.0) {
            out.add(warn(path, "W_RANGE_01", "range 建议在 0..1（当前=" + a + "," + b + "）"));
        }
        if (Math.abs(a - b) < 1e-9) {
            out.add(warn(path, "W_RANGE_ZERO", "range 长度为 0，可能无法形成有效拼缝"));
        }
    }

    private static void validateEndpointRef(List<AssemblyValidationIssue> out, String path, Object v, Set<String> ids) {
        switch (v) {
            case null -> {
                out.add(err(path, "E_ENDPOINT_MISSING", "缺少端点"));
                return;
            }
            case String s -> {
                String t = s.trim();
                if (t.isEmpty()) {
                    out.add(err(path, "E_ENDPOINT_EMPTY", "端点字符串不能为空"));
                    return;
                }
                int dot = t.indexOf('.');
                if (dot > 0) {
                    String id = t.substring(0, dot).trim();
                    if (!id.isEmpty() && !ids.isEmpty() && !ids.contains(id)) {
                        out.add(warn(path, "W_ENDPOINT_UNKNOWN_COMPONENT", "引用了不存在的组件 id: " + id + "（示例/训练建议修正）"));
                    }
                }
                return;
            }
            case Map<?, ?> m -> {
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
            default -> {
            }
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

    private static void validatePointsXZ(List<AssemblyValidationIssue> out, String path, List<?> pts) {
        for (int i = 0; i < pts.size(); i++) {
            Object it = pts.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> m)) {
                out.add(err(p, "E_POINT_NOT_OBJECT", "point 必须是 {x,z} 对象"));
                continue;
            }
            if (m.get("x") == null || m.get("z") == null) {
                out.add(err(p, "E_POINT_MISSING_XZ", "point 必须包含 x/z"));
            }
        }
    }

    private static void validatePointRefXYZ(List<AssemblyValidationIssue> out, String path, Object v, String missingCode) {
        if (v == null) {
            out.add(err(path, missingCode, "缺少坐标点 {x,y,z}"));
            return;
        }
        if (!(v instanceof Map<?, ?> m)) {
            out.add(err(path, "E_POINT_REF_TYPE", "必须是 {x,y,z} 对象"));
            return;
        }
        if (m.get("x") == null || m.get("y") == null || m.get("z") == null) {
            out.add(err(path, "E_POINT_MISSING_XYZ", "必须包含 x/y/z"));
        }
    }

    private static void requireIntIfPresent(List<AssemblyValidationIssue> out, String base, Map<?, ?> m, String key) {
        if (m.get(key) == null) return;
        if (intOrNull(m.get(key)) == null) out.add(err(base + "." + key, "E_INT_TYPE", key + " 必须是整数"));
    }

    private static void validateTerrainAdaptation(List<AssemblyValidationIssue> out, String path, Object v, String context) {
        if (!(v instanceof Map<?, ?> ta)) {
            out.add(err(path, "E_TA_TYPE", "terrainAdaptation 必须是对象（map）"));
            return;
        }

        warnUnknownKeys(out, path, ta, java.util.Set.of(
                "mode",
                "base_level", "baseLevel", "fixedY",
                "max_step_height", "maxStepHeight", "max_step", "maxStep",
                "foundation_depth", "foundationDepth",
                "anchorMaxDepth",
                "clearHeight",
                "allowWaterEdit", "allowLavaEdit",
                "foundationMaterial", "anchorMaterial",
                "embedDepth",
                "floatHeight"
        ));

        Object mv = ta.get("mode");
        String mode = (mv == null) ? "" : String.valueOf(mv).trim().toUpperCase(Locale.ROOT);
        if (mode.isEmpty()) {
            out.add(err(path + ".mode", "E_TA_MODE_MISSING", "terrainAdaptation.mode 缺失"));
            return;
        }
        // Normalize common synonyms
        if (mode.equals("CUT_AND_FILL")) mode = "FLATTEN";
        if (mode.equals("HANG") || mode.equals("HANGING")) mode = "ANCHOR";

        boolean ok = mode.equals("FLATTEN") || mode.equals("DRAPE") || mode.equals("ANCHOR") || mode.equals("EMBED") || mode.equals("FLOAT");
        if (!ok) {
            out.add(err(path + ".mode", "E_TA_MODE_VALUE", "terrainAdaptation.mode 取值非法: " + mode + "（允许 FLATTEN/DRAPE/ANCHOR/EMBED/FLOAT）"));
            return;
        }

        // base level strategy (optional): AVERAGE / MEDIAN / MODE / LOWEST / HIGHEST / FIXED
        Object bl = ta.get("baseLevel");
        if (bl == null) bl = ta.get("base_level");
        if (bl != null) {
            if (bl instanceof Number) {
                // LLM sometimes puts a number here; suggest fixedY instead.
                out.add(warn(path + ".baseLevel", "W_TA_BASELEVEL_NUM", "baseLevel/base_level 不应为数字；请使用 baseLevel=FIXED + fixedY"));
            } else {
                String s = String.valueOf(bl).trim();
                if (s.isEmpty()) {
                    out.add(err(path + ".baseLevel", "E_TA_BASELEVEL_EMPTY", "baseLevel/base_level 不能为空"));
                } else {
                    String u = getString(s);
                    boolean bok = u.equals("AVERAGE") || u.equals("MEDIAN") || u.equals("MODE") || u.equals("LOWEST") || u.equals("HIGHEST") || u.equals("FIXED");
                    if (!bok) {
                        out.add(err(path + ".baseLevel", "E_TA_BASELEVEL_VALUE",
                                "baseLevel/base_level 取值非法: " + s + "（允许 AVERAGE/MEDIAN/MODE/LOWEST/HIGHEST/FIXED）"));
                    }
                    if (u.equals("FIXED")) {
                        Object fy = ta.get("fixedY");
                        if (fy == null || intOrNull(fy) == null) {
                            out.add(err(path + ".fixedY", "E_TA_FIXEDY_REQUIRED", "baseLevel=FIXED 时必须提供 fixedY（整数）"));
                        }
                    }
                }
            }
        }

        // Shared numeric sanity (only validate if provided)
        // DRAPE
        if (mode.equals("DRAPE")) {
            Object ms = ta.get("max_step_height");
            if (ms == null) ms = ta.get("maxStepHeight");
            if (ms == null) ms = ta.get("max_step");
            if (ms == null) ms = ta.get("maxStep");
            if (ms != null) {
                Integer x = intOrNull(ms);
                if (x == null) out.add(err(path + ".max_step_height", "E_INT_TYPE", "max_step_height 必须是整数"));
                else if (x < 0 || x > 8) out.add(err(path + ".max_step_height", "E_INT_RANGE", "max_step_height 建议范围 0..8"));
            }
            Object fd = ta.get("foundation_depth");
            if (fd == null) fd = ta.get("foundationDepth");
            if (fd != null) {
                Integer x = intOrNull(fd);
                if (x == null) out.add(err(path + ".foundation_depth", "E_INT_TYPE", "foundation_depth 必须是整数"));
                else if (x < 0 || x > 32) out.add(err(path + ".foundation_depth", "E_INT_RANGE", "foundation_depth 建议范围 0..32"));
            }
            Object ch = ta.get("clearHeight");
            if (ch != null) {
                Integer x = intOrNull(ch);
                if (x == null) out.add(err(path + ".clearHeight", "E_INT_TYPE", "clearHeight 必须是整数"));
                else if (x < 0 || x > 16) out.add(err(path + ".clearHeight", "E_INT_RANGE", "clearHeight 建议范围 0..16"));
            }
        }

        // ANCHOR
        if (mode.equals("ANCHOR")) {
            Object ad = ta.get("anchorMaxDepth");
            if (ad != null) {
                Integer x = intOrNull(ad);
                if (x == null) out.add(err(path + ".anchorMaxDepth", "E_INT_TYPE", "anchorMaxDepth 必须是整数"));
                else if (x < 1 || x > 256) out.add(err(path + ".anchorMaxDepth", "E_INT_RANGE", "anchorMaxDepth 建议范围 1..256"));
            }
            Object fy = ta.get("fixedY");
            if (fy != null) {
                Integer x = intOrNull(fy);
                if (x == null) out.add(err(path + ".fixedY", "E_INT_TYPE", "fixedY 必须是整数"));
            }
        }

        // EMBED / FLOAT (very light checks)
        if (mode.equals("EMBED")) {
            Object ed = ta.get("embedDepth");
            if (ed != null) {
                Integer x = intOrNull(ed);
                if (x == null) out.add(err(path + ".embedDepth", "E_INT_TYPE", "embedDepth 必须是整数"));
                else if (x < 0 || x > 128) out.add(err(path + ".embedDepth", "E_INT_RANGE", "embedDepth 建议范围 0..128"));
            }
        }
        if (mode.equals("FLOAT")) {
            Object fh = ta.get("floatHeight");
            if (fh != null) {
                Integer x = intOrNull(fh);
                if (x == null) out.add(err(path + ".floatHeight", "E_INT_TYPE", "floatHeight 必须是整数"));
            }
        }

        // Extra context hint (warning only): usage mismatch
        if (context != null) {
            String c = context.toUpperCase(Locale.ROOT);
            if (c.contains("CONNECTION:BRIDGE") && mode.equals("DRAPE")) {
                out.add(warn(path + ".mode", "W_TA_MODE_MISMATCH", "BRIDGE 连接通常建议使用 ANCHOR（当前为 DRAPE）"));
            }
            if ((c.contains("CONNECTION:PATH") || c.contains("CONNECTION:WALL")) && mode.equals("ANCHOR")) {
                out.add(warn(path + ".mode", "W_TA_MODE_MISMATCH", "PATH/WALL 连接通常建议使用 DRAPE（当前为 ANCHOR）"));
            }
        }
    }

    private static @NotNull String getString(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        // Chinese & common aliases
        if (u.contains("平均") || u.contains("AVG") || u.contains("AVER")) u = "AVERAGE";
        if (u.contains("中位") || u.contains("MED")) u = "MEDIAN";
        if (u.contains("众数") || u.contains("MODE")) u = "MODE";
        if (u.contains("最低") || u.contains("LOW")) u = "LOWEST";
        if (u.contains("最高") || u.contains("HIGH")) u = "HIGHEST";
        if (u.contains("固定") || u.contains("FIX")) u = "FIXED";
        return u;
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

            // Suggest a likely intended key (no auto-fix here; suggestion only).
            String sug = suggestKey(key, allowed);
            if (sug != null && !sug.equals(key)) {
                out.add(warn(base + "." + key, "W_SUGGEST_KEY", "你是不是想写: " + sug));
            }
        }
    }

    private static String suggestKey(String key, Set<String> allowed) {
        if (key == null || allowed == null || allowed.isEmpty()) return null;
        String k = key.trim();
        if (k.isEmpty()) return null;
        // Avoid suggesting for block ids or very long keys.
        if (k.length() > 32) return null;
        if (k.contains(":")) return null;

        String nk = normKey(k);
        if (nk.isEmpty()) return null;

        String best = null;
        int bestD = Integer.MAX_VALUE;
        int bestCount = 0;
        for (String cand : allowed) {
            if (cand == null) continue;
            int d = editDistance(nk, normKey(cand), 2);
            if (d < 0) continue;
            if (d < bestD) {
                bestD = d;
                best = cand;
                bestCount = 1;
            } else if (d == bestD) {
                bestCount++;
            }
        }
        if (best == null) return null;
        if (bestCount != 1) return null;
        if (bestD > 2) return null;
        // Keep conservative: distance-2 only for longer keys.
        if (bestD == 2 && nk.length() < 7) return null;
        return best;
    }

    private static String normKey(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        return t.replace("_", "").replace("-", "").replace(" ", "");
    }

    /**
     * Levenshtein edit distance with an early-exit cap.
     * Returns -1 if distance exceeds cap.
     */
    private static int editDistance(String a, String b, int cap) {
        if (a == null || b == null) return -1;
        if (a.equals(b)) return 0;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > cap) return -1;
        if (la == 0) return lb <= cap ? lb : -1;
        if (lb == 0) return la <= cap ? la : -1;

        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;

        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int minRow = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                cur[j] = v;
                if (v < minRow) minRow = v;
            }
            if (minRow > cap) return -1;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        int d = prev[lb];
        return d <= cap ? d : -1;
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


