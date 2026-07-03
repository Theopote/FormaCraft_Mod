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
            out.add(AssemblyValidationSupport.err("$", "E_ROOT_TYPE", "extra.assembly 必须是 JSON 对象（map）"));
            return out;
        }

        // Root unknown-key warnings (training-friendly)
        AssemblyValidationSupport.warnUnknownKeys(out, "$", root, Set.of(
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
            out.add(AssemblyValidationSupport.err("$.macro", "E_MACRO_TYPE", "macro 必须是对象（map）"));
        }

        // ops form
        Object opsObj = root.get("ops");
        if (opsObj instanceof List<?> opsList) {
            AssemblyOpValidator.validateOps(out, "$.ops", opsList);
        }

        // graph/components form
        Object graphObj = root.get("graph");
        Map<?, ?> graph = (graphObj instanceof Map<?, ?> gm) ? gm : null;
        if (graph != null) {
            AssemblyValidationSupport.warnUnknownKeys(out, "$.graph", graph, Set.of("components", "connections"));
        }
        Object compsObj = (graph != null) ? graph.get("components") : root.get("components");
        Object connsObj = (graph != null) ? graph.get("connections") : root.get("connections");

        Set<String> componentIds = new HashSet<>();
        if (compsObj instanceof List<?> comps) {
            validateComponents(out, graph != null ? "$.graph.components" : "$.components", comps, componentIds);
        } else if (opsObj == null) {
            // If neither ops nor components exist, that's almost certainly wrong.
            out.add(AssemblyValidationSupport.err("$", "E_MISSING_OPS_OR_COMPONENTS", "extra.assembly 缺少 ops 或 graph.components/components"));
        }

        if (connsObj instanceof List<?> conns) {
            validateConnections(out, graph != null ? "$.graph.connections" : "$.connections", conns, componentIds);
        }

        return out;
    }

    private static void validateMacro(List<AssemblyValidationIssue> out, Map<?, ?> macro) {
        AssemblyValidationSupport.warnUnknownKeys(out, "$.macro", macro, Set.of(
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
                if (!ok) out.add(AssemblyValidationSupport.warn("$.macro" + ".shapeType", "W_MACRO_SHAPE_VALUE", "shapeType 可能不可识别: " + st));
            }
        }

        Object hs = macro.get("heightScale");
        if (hs == null) hs = macro.get("height_scale");
        if (hs != null) {
            if (!(hs instanceof Number) && !(hs instanceof String)) {
                out.add(AssemblyValidationSupport.err("$.macro" + ".heightScale", "E_MACRO_HEIGHTSCALE_TYPE", "heightScale 必须是数字或枚举字符串"));
            }
        }

        Object op = macro.get("openness");
        if (op != null) {
            Double d = AssemblyValidationSupport.doubleOrNull(op);
            if (d == null) out.add(AssemblyValidationSupport.err("$.macro" + ".openness", "E_MACRO_OPENNESS_TYPE", "openness 必须是 0..1 的数字"));
            else if (d < 0.0 || d > 1.0) out.add(AssemblyValidationSupport.warn("$.macro" + ".openness", "W_MACRO_OPENNESS_RANGE", "openness 建议范围 0..1（当前=" + d + "）"));
        }

        Object rt = macro.get("roofType");
        if (rt == null) rt = macro.get("roof_type");
        if (rt != null) {
            String s = String.valueOf(rt).trim().toUpperCase(Locale.ROOT);
            if (!s.isEmpty() && !(s.equals("FLAT") || s.equals("GABLE"))) {
                out.add(AssemblyValidationSupport.warn("$.macro" + ".roofType", "W_MACRO_ROOFTYPE_VALUE", "roofType 当前仅支持 FLAT/GABLE（当前=" + rt + "）"));
            }
        }

        Object bt = macro.get("bridgeTower");
        if (bt == null) bt = macro.get("bridge_tower");
        if (bt != null) {
            if (!(bt instanceof Map<?, ?>) && !(bt instanceof Boolean)) {
                out.add(AssemblyValidationSupport.warn("$.macro" + ".bridgeTower", "W_MACRO_BRIDGETOWER_TYPE", "bridgeTower 建议是对象（map）或 true（启用默认注入）"));
            }
        }

        Object styleObj = macro.get("style");
        if (styleObj == null) styleObj = macro.get("culture");
        if (styleObj != null) {
            if (!(styleObj instanceof Map<?, ?> m)) {
                out.add(AssemblyValidationSupport.warn("$.macro" + ".style", "W_MACRO_STYLE_TYPE", "style/culture 建议是对象（map）"));
            } else {
                AssemblyValidationSupport.warnUnknownKeys(out, "$.macro" + ".style", m, Set.of(
                        "styleId", "style_id", "id",
                        "intent", "mood",
                        "density", "symmetry", "verticality", "transparency",
                        "structureExposure", "structure_exposure"
                ));
                // numeric sliders: 0..1 recommended
                for (String k : new String[]{"density","symmetry","verticality","transparency","structureExposure","structure_exposure"}) {
                    Object v = m.get(k);
                    if (v == null) continue;
                    Double d = AssemblyValidationSupport.doubleOrNull(v);
                    if (d == null) out.add(AssemblyValidationSupport.err("$.macro" + ".style." + k, "E_MACRO_STYLE_SLIDER_TYPE", k + " 必须是数字"));
                    else if (d < 0.0 || d > 1.0) out.add(AssemblyValidationSupport.warn("$.macro" + ".style." + k, "W_MACRO_STYLE_SLIDER_RANGE", k + " 建议范围 0..1（当前=" + d + "）"));
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
                out.add(AssemblyValidationSupport.err(p, "E_COMPONENT_NOT_OBJECT", "component 必须是对象（map）"));
                continue;
            }
            AssemblyValidationSupport.warnUnknownKeys(out, p, c, Set.of(
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
            String id = AssemblyValidationSupport.str(c.get("id"), "").trim();
            if (!id.isEmpty()) {
                if (!idsOut.add(id)) out.add(AssemblyValidationSupport.err(p + ".id", "E_COMPONENT_ID_DUP", "重复的 component id: " + id));
            }

            String type = AssemblyValidationSupport.str(c.get("type"), AssemblyValidationSupport.str(c.get("op"), "")).trim().toUpperCase(Locale.ROOT);
            if (type.isBlank()) {
                out.add(AssemblyValidationSupport.err(p + ".type", "E_COMPONENT_TYPE_MISSING", "缺少 type（或 op）字段"));
                continue;
            }

            // P0: validate known component kinds we heavily use
            if (type.contains("SPLINE")) {
                Object pts = c.get("points");
                if (!(pts instanceof List<?> l) || l.size() < 2) {
                    out.add(AssemblyValidationSupport.err(p + ".points", "E_SPLINE_POINTS_MIN2", "SPLINE_SWEEP.points 至少需要 2 个点"));
                } else {
                    AssemblyValidationSupport.validatePoints(out, p + ".points", l);
                }
            }

            if (type.contains("SHELL_BOX") || type.contains("BOX_SHELL")) {
                AssemblyValidationSupport.requireIntMin(out, p, c, "w", 1);
                AssemblyValidationSupport.requireIntMin(out, p, c, "d", 1);
                AssemblyValidationSupport.requireIntMin(out, p, c, "h", 2);
                validateFacadeMacros(out, p, c);
            }
            if (type.contains("CYLINDER")) {
                // allow defaults but validate if provided
                if (c.get("r") != null || c.get("radius") != null) {
                    Integer rv = AssemblyValidationSupport.intOrNull(c.get("r"));
                    if (rv == null) rv = AssemblyValidationSupport.intOrNull(c.get("radius"));
                    if (rv == null) out.add(AssemblyValidationSupport.err(p + ".r", "E_INT_TYPE", "r/radius 必须是整数"));
                    else if (rv < 2) out.add(AssemblyValidationSupport.err(p + ".r", "E_INT_RANGE", "r/radius 必须 >= 2"));
                }
                if (c.get("h") != null || c.get("height") != null) {
                    Integer hv = AssemblyValidationSupport.intOrNull(c.get("h"));
                    if (hv == null) hv = AssemblyValidationSupport.intOrNull(c.get("height"));
                    if (hv == null) out.add(AssemblyValidationSupport.err(p + ".h", "E_INT_TYPE", "h/height 必须是整数"));
                    else if (hv < 1) out.add(AssemblyValidationSupport.err(p + ".h", "E_INT_RANGE", "h/height 必须 >= 1"));
                }
                if (c.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, c, "thickness", 1);
            }
            if (type.contains("EXTRUDE")) {
                if (c.get("h") != null || c.get("height") != null) AssemblyValidationSupport.requireIntMin(out, p, c, "h", 1);
                if (c.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, c, "thickness", 1);
                String shape = AssemblyValidationSupport.str(c.get("shape"), "RECT").trim().toUpperCase(Locale.ROOT);
                if ("RECT".equals(shape)) {
                    if (c.get("w") != null) AssemblyValidationSupport.requireIntMin(out, p, c, "w", 1);
                    if (c.get("d") != null) AssemblyValidationSupport.requireIntMin(out, p, c, "d", 1);
                } else {
                    Object pts = c.get("points");
                    if (pts != null && (!(pts instanceof List<?> l) || l.size() < 3)) {
                        out.add(AssemblyValidationSupport.err(p + ".points", "E_EXTRUDE_POINTS_MIN3", "EXTRUDE_POLYGON.points 至少需要 3 个点（每个点需含 x/z）"));
                    }
                }
            }
            if (type.contains("ROOF")) {
                Object rt = c.get("roofType");
                if (rt == null) rt = c.get("kind");
                if (rt != null) {
                    String t = String.valueOf(rt).trim().toUpperCase(Locale.ROOT);
                    if (!(t.equals("FLAT") || t.equals("GABLE"))) {
                        out.add(AssemblyValidationSupport.err(p + ".roofType", "E_ROOF_TYPE_VALUE", "ROOF_COVER.type 取值非法（允许 FLAT/GABLE）: " + t));
                    }
                }
            }
            if (type.contains("BSP")) {
                Object cfg = c.get("config");
                if (cfg == null) cfg = c.get("floor_plan_logic");
                if (cfg == null) cfg = c.get("floorPlanLogic");
                if (cfg == null) {
                    out.add(AssemblyValidationSupport.err(p + ".config", "E_BSP_CONFIG_MISSING", "BSP_FLOOR_PLAN 需要 config/floor_plan_logic"));
                }
            }
        }
    }

    private static void validateFacadeMacros(List<AssemblyValidationIssue> out, String compPath, Map<?, ?> comp) {
        Object facadeObj = comp.get("facade");
        if (facadeObj == null) facadeObj = comp.get("facade_logic");
        if (facadeObj == null) facadeObj = comp.get("facadeLogic");
        if (!(facadeObj instanceof Map<?, ?> facade)) return;
        AssemblyValidationSupport.warnUnknownKeys(out, compPath + ".facade", facade, java.util.Set.of(
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
            AssemblyValidationSupport.warnUnknownKeys(out, p, m, java.util.Set.of(
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
            if (m.get("face") != null || m.get("faces") != null) AssemblyValidationSupport.requireFacesString(out, p, m);
            AssemblyValidationSupport.requireIntMin(out, p, m, "bayW", 1);
            AssemblyValidationSupport.requireIntMin(out, p, m, "bayH", 1);
            // spandrel optional
            if (m.get("spandrelEvery") != null || m.get("spEvery") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "spandrelEvery", 0);
            if (m.get("spandrelHeight") != null || m.get("spH") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "spandrelHeight", 0);
        }

        // surfacePattern
        Object sp = facade.get("surfacePattern");
        if (sp == null) sp = facade.get("pattern");
        if (sp instanceof Map<?, ?> m) {
            String p = compPath + ".facade.surfacePattern";
            AssemblyValidationSupport.warnUnknownKeys(out, p, m, java.util.Set.of(
                    "face", "faces",
                    "type", "pattern",
                    "step", "spacing", "thickness",
                    "material", "accent", "frame"
            ));
            if (m.get("face") != null || m.get("faces") != null) AssemblyValidationSupport.requireFacesString(out, p, m);
            String pattern = AssemblyValidationSupport.str(m.get("pattern"), AssemblyValidationSupport.str(m.get("type"), "GRID")).trim().toUpperCase(Locale.ROOT);
            if (!(pattern.equals("GRID") || pattern.equals("STRIPES_V") || pattern.equals("STRIPES_H") || pattern.equals("RIBS_V") || pattern.equals("RIBS_H")
                    || pattern.equals("STRIPES_VERTICAL") || pattern.equals("STRIPES_HORIZONTAL") || pattern.equals("RIBS_VERTICAL") || pattern.equals("RIBS_HORIZONTAL")
                    || pattern.equals("NOISE"))) {
                out.add(AssemblyValidationSupport.err(p + ".pattern", "E_PATTERN_VALUE", "surfacePattern.pattern 取值非法: " + pattern));
            }
            // step/thickness can be omitted; only validate if present (not required for NOISE)
            if (!pattern.equals("NOISE")) {
                if (m.get("step") != null || m.get("spacing") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "step", 1);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
            }
            // Forma-Gene integration: NOISE pattern parameters
            if (pattern.equals("NOISE")) {
                if (m.get("noiseProbability") != null || m.get("noise_probability") != null) {
                    Object np = m.get("noiseProbability") != null ? m.get("noiseProbability") : m.get("noise_probability");
                    if (np instanceof Number) {
                        double v = ((Number) np).doubleValue();
                        if (v < 0.0 || v > 1.0) {
                            out.add(AssemblyValidationSupport.warn(p + ".noiseProbability", "W_NOISE_PROB_RANGE", "noiseProbability 建议在 0.0~1.0 范围内"));
                        }
                    }
                }
            }
        }

        // surfaceBands
        Object sb = facade.get("surfaceBands");
        if (sb == null) sb = facade.get("SURFACE_BANDS");
        if (sb == null) sb = facade.get("bands");
        if (sb instanceof Map<?, ?> m) {
            String p = compPath + ".facade.surfaceBands";
            AssemblyValidationSupport.warnUnknownKeys(out, p, m, java.util.Set.of(
                    "face", "faces",
                    "horizontalBands", "hBands", "bandsH",
                    "verticalBands", "vBands", "bandsV"
            ));
            if (m.get("face") != null || m.get("faces") != null) AssemblyValidationSupport.requireFacesString(out, p, m);
            Object hb = m.get("horizontalBands");
            if (hb == null) hb = m.get("hBands");
            if (hb == null) hb = m.get("bandsH");
            if (hb != null && !(hb instanceof List<?>)) out.add(AssemblyValidationSupport.err(p + ".horizontalBands", "E_BANDS_H_NOT_LIST", "horizontalBands 必须是数组"));

            Object vb = m.get("verticalBands");
            if (vb == null) vb = m.get("vBands");
            if (vb == null) vb = m.get("bandsV");
            if (vb != null && !(vb instanceof List<?>)) out.add(AssemblyValidationSupport.err(p + ".verticalBands", "E_BANDS_V_NOT_LIST", "verticalBands 必须是数组"));

            // Validate band entries (training stability)
            if (hb instanceof List<?> hList) {
                for (int i = 0; i < hList.size(); i++) {
                    Object it = hList.get(i);
                    String bp = p + ".horizontalBands[" + i + "]";
                    if (!(it instanceof Map<?, ?> bm)) {
                        out.add(AssemblyValidationSupport.err(bp, "E_BAND_H_NOT_OBJECT", "horizontalBands 条目必须是对象（map）"));
                        continue;
                    }
                    AssemblyValidationSupport.warnUnknownKeys(out, bp, bm, java.util.Set.of(
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
                    if (yv == null) out.add(AssemblyValidationSupport.err(bp + ".y", "E_BAND_H_Y_MISSING", "horizontalBands[].y 缺失"));
                    else if (AssemblyValidationSupport.intOrNull(yv) == null) out.add(AssemblyValidationSupport.err(bp + ".y", "E_INT_TYPE", "y 必须是整数"));

                    // optional ints with ranges
                    if (bm.get("height") != null || bm.get("h") != null) AssemblyValidationSupport.requireIntMin(out, bp, bm, "height", 1);
                    if (bm.get("inset") != null) AssemblyValidationSupport.requireIntMin(out, bp, bm, "inset", 0);
                    if (bm.get("outset") != null || bm.get("out") != null) {
                        Object ov = bm.get("outset");
                        if (ov == null) ov = bm.get("out");
                        if (AssemblyValidationSupport.intOrNull(ov) == null) out.add(AssemblyValidationSupport.err(bp + ".outset", "E_INT_TYPE", "outset 必须是整数"));
                        else if (AssemblyValidationSupport.intOrNull(ov) < 0) out.add(AssemblyValidationSupport.err(bp + ".outset", "E_INT_RANGE", "outset 必须 >= 0"));
                    }
                    if (bm.get("depth") != null) AssemblyValidationSupport.requireIntMin(out, bp, bm, "depth", 1);
                }
            }

            if (vb instanceof List<?> vList) {
                for (int i = 0; i < vList.size(); i++) {
                    Object it = vList.get(i);
                    String bp = p + ".verticalBands[" + i + "]";
                    if (!(it instanceof Map<?, ?> bm)) {
                        out.add(AssemblyValidationSupport.err(bp, "E_BAND_V_NOT_OBJECT", "verticalBands 条目必须是对象（map）"));
                        continue;
                    }
                    AssemblyValidationSupport.warnUnknownKeys(out, bp, bm, java.util.Set.of(
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
                    if (sv == null) out.add(AssemblyValidationSupport.err(bp + ".step", "E_BAND_V_STEP_MISSING", "verticalBands[].step 缺失"));
                    else if (AssemblyValidationSupport.intOrNull(sv) == null) out.add(AssemblyValidationSupport.err(bp + ".step", "E_INT_TYPE", "step 必须是整数"));
                    else if (AssemblyValidationSupport.intOrNull(sv) < 1) out.add(AssemblyValidationSupport.err(bp + ".step", "E_INT_RANGE", "step 必须 >= 1"));

                    Object wv = bm.get("width");
                    if (wv == null) wv = bm.get("thickness");
                    if (wv == null) out.add(AssemblyValidationSupport.err(bp + ".width", "E_BAND_V_WIDTH_MISSING", "verticalBands[].width 缺失"));
                    else if (AssemblyValidationSupport.intOrNull(wv) == null) out.add(AssemblyValidationSupport.err(bp + ".width", "E_INT_TYPE", "width 必须是整数"));
                    else if (AssemblyValidationSupport.intOrNull(wv) < 1) out.add(AssemblyValidationSupport.err(bp + ".width", "E_INT_RANGE", "width 必须 >= 1"));

                    if (bm.get("offset") != null && AssemblyValidationSupport.intOrNull(bm.get("offset")) == null) {
                        out.add(AssemblyValidationSupport.err(bp + ".offset", "E_INT_TYPE", "offset 必须是整数"));
                    }
                    if (bm.get("y0") != null && AssemblyValidationSupport.intOrNull(bm.get("y0")) == null) out.add(AssemblyValidationSupport.err(bp + ".y0", "E_INT_TYPE", "y0 必须是整数"));
                    if (bm.get("y1") != null && AssemblyValidationSupport.intOrNull(bm.get("y1")) == null) out.add(AssemblyValidationSupport.err(bp + ".y1", "E_INT_TYPE", "y1 必须是整数"));
                    if (bm.get("inset") != null) AssemblyValidationSupport.requireIntMin(out, bp, bm, "inset", 0);
                    if (bm.get("outset") != null || bm.get("out") != null) {
                        Object ov = bm.get("outset");
                        if (ov == null) ov = bm.get("out");
                        if (AssemblyValidationSupport.intOrNull(ov) == null) out.add(AssemblyValidationSupport.err(bp + ".outset", "E_INT_TYPE", "outset 必须是整数"));
                        else if (AssemblyValidationSupport.intOrNull(ov) < 0) out.add(AssemblyValidationSupport.err(bp + ".outset", "E_INT_RANGE", "outset 必须 >= 0"));
                    }
                    if (bm.get("depth") != null) AssemblyValidationSupport.requireIntMin(out, bp, bm, "depth", 1);
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
                    out.add(AssemblyValidationSupport.err(p, "E_OPENING_NOT_OBJECT", "opening 条目必须是对象（map）"));
                    continue;
                }
                AssemblyValidationSupport.warnUnknownKeys(out, p, m, java.util.Set.of(
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
                if (m.get("face") != null || m.get("faces") != null) AssemblyValidationSupport.requireFacesString(out, p, m);

                String kind = AssemblyValidationSupport.str(m.get("kind"), AssemblyValidationSupport.str(m.get("type"), "")).trim().toUpperCase(Locale.ROOT);
                if (kind.isBlank()) {
                    out.add(AssemblyValidationSupport.err(p + ".kind", "E_OPENINGS_KIND_MISSING", "openings[].kind 缺失"));
                } else {
                    boolean ok = kind.equals("WINDOW_GRID") || kind.equals("DOOR") || kind.equals("ARCH_WINDOW") || kind.equals("ROSE_WINDOW");
                    if (!ok) out.add(AssemblyValidationSupport.err(p + ".kind", "E_OPENINGS_KIND_VALUE", "openings[].kind 取值非法: " + kind));
                }

                // If provided, validate sizing knobs. (Engine has defaults; do not require them.)
                if (m.get("rows") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "rows", 1);
                if (m.get("cols") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "cols", 1);
                if (m.get("winW") != null || m.get("w") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "winW", 1);
                if (m.get("winH") != null || m.get("h") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "winH", 1);
                if (m.get("doorW") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "doorW", 1);
                if (m.get("doorH") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "doorH", 2);

                // Rose window sanity
                if (m.get("r") != null || m.get("radius") != null) {
                    Integer rv = AssemblyValidationSupport.intOrNull(m.get("r"));
                    if (rv == null) rv = AssemblyValidationSupport.intOrNull(m.get("radius"));
                    if (rv != null && rv < 2) out.add(AssemblyValidationSupport.err(p + ".r", "E_ROSE_R_RANGE", "ROSE_WINDOW.r 必须 >= 2"));
                }
                if (m.get("petals") != null || m.get("spokes") != null) {
                    Integer pv = AssemblyValidationSupport.intOrNull(m.get("petals"));
                    if (pv == null) pv = AssemblyValidationSupport.intOrNull(m.get("spokes"));
                    if (pv != null && pv < 3) out.add(AssemblyValidationSupport.err(p + ".petals", "E_ROSE_PETALS_RANGE", "ROSE_WINDOW.petals 必须 >= 3"));
                }
                // Arch type sanity
                if (m.get("archType") != null || m.get("arch") != null) {
                    String at = AssemblyValidationSupport.str(m.get("archType"), AssemblyValidationSupport.str(m.get("arch"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!at.isEmpty() && !(at.equals("ROUND") || at.equals("POINTED"))) {
                        out.add(AssemblyValidationSupport.err(p + ".archType", "E_ARCH_TYPE_VALUE", "ARCH_WINDOW.archType 取值非法: " + at + "（允许 ROUND/POINTED）"));
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
                out.add(AssemblyValidationSupport.err(p, "E_CONN_NOT_OBJECT", "connection 必须是对象（map）"));
                continue;
            }
            AssemblyValidationSupport.warnUnknownKeys(out, p, c, Set.of(
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
            AssemblyValidationSupport.validateEndpointRef(out, p + ".from", c.get("from"), componentIds);
            AssemblyValidationSupport.validateEndpointRef(out, p + ".to", c.get("to"), componentIds);
            if (c.get("terrainAdaptation") != null) {
                String type = AssemblyValidationSupport.str(c.get("type"), "CONNECTOR_LINE").trim().toUpperCase(Locale.ROOT);
                AssemblyValidationSupport.validateTerrainAdaptation(out, p + ".terrainAdaptation", c.get("terrainAdaptation"), "CONNECTION:" + type);
            }
        }
    }

}
