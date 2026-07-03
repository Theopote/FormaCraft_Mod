package com.formacraft.server.assembly.validation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates surface / implicit / spline assembly ops. */
final class AssemblyOpSurfaceValidator {
    private AssemblyOpSurfaceValidator() {}

    static void validate(String op, List<AssemblyValidationIssue> out, String p, Map<?, ?> m) {

            if (op.equals("BEZIER_SURFACE")) {
                Object pts = m.get("points");
                if (!(pts instanceof List<?> list)) {
                    out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINTS_MISSING", "BEZIER_SURFACE.points 必须是数组（16 点或 4x4 网格）"));
                } else {
                    int count = 0;
                    if (!list.isEmpty() && list.getFirst() instanceof List<?>) {
                        for (Object rowObj : list) {
                            if (!(rowObj instanceof List<?> row)) continue;
                            for (Object po : row) {
                                if (po instanceof Map<?, ?> pm) {
                                    count++;
                                    if (AssemblyValidationSupport.intOrNull(pm.get("x")) == null) out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINT_X", "控制点 x 必须是整数"));
                                    if (AssemblyValidationSupport.intOrNull(pm.get("y")) == null) out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINT_Y", "控制点 y 必须是整数"));
                                    if (AssemblyValidationSupport.intOrNull(pm.get("z")) == null) out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINT_Z", "控制点 z 必须是整数"));
                                }
                            }
                        }
                    } else {
                        for (Object po : list) {
                            if (po instanceof Map<?, ?> pm) {
                                count++;
                                if (AssemblyValidationSupport.intOrNull(pm.get("x")) == null) out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINT_X", "控制点 x 必须是整数"));
                                if (AssemblyValidationSupport.intOrNull(pm.get("y")) == null) out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINT_Y", "控制点 y 必须是整数"));
                                if (AssemblyValidationSupport.intOrNull(pm.get("z")) == null) out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINT_Z", "控制点 z 必须是整数"));
                            }
                        }
                    }
                    if (count != 16) out.add(AssemblyValidationSupport.err(p + ".points", "E_BEZIER_POINTS_COUNT", "BEZIER_SURFACE 需要 16 个控制点（当前=" + count + "）"));
                }
                if (m.get("uSamples") != null || m.get("u") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("vSamples") != null || m.get("v") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "vSamples", 2);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(AssemblyValidationSupport.err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
                if (m.get("connectMaxStep") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "connectMaxStep", 1);
            }
            if (op.equals("BEZIER_SURFACE_SET")) {
                Object patches = m.get("patches");
                if (!(patches instanceof List<?> pl) || pl.isEmpty()) {
                    out.add(AssemblyValidationSupport.err(p + ".patches", "E_BEZIER_SET_PATCHES", "BEZIER_SURFACE_SET.patches 必须是数组且非空"));
                } else {
                    for (int pi = 0; pi < pl.size(); pi++) {
                        Object it2 = pl.get(pi);
                        String pp = p + ".patches[" + pi + "]";
                        if (!(it2 instanceof Map<?, ?> pm)) {
                            out.add(AssemblyValidationSupport.err(pp, "E_BEZIER_SET_PATCH_NOT_OBJECT", "patch 条目必须是对象（map）"));
                            continue;
                        }
                        AssemblyValidationSupport.warnUnknownKeys(out, pp, pm, java.util.Set.of(
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
                        if (!(pts instanceof List<?>)) out.add(AssemblyValidationSupport.err(pp + ".points", "E_BEZIER_POINTS_MISSING", "patch.points 必须是数组（16 点或 4x4 网格）"));
                        // at
                        Object at = pm.get("at");
                        if (at != null && !(at instanceof Map<?, ?>)) out.add(AssemblyValidationSupport.err(pp + ".at", "E_BEZIER_SET_AT_TYPE", "at 必须是对象（map）"));
                        // optional ints
                        if (pm.get("uSamples") != null || pm.get("u") != null) AssemblyValidationSupport.requireIntMin(out, pp, pm, "uSamples", 2);
                        if (pm.get("vSamples") != null || pm.get("v") != null) AssemblyValidationSupport.requireIntMin(out, pp, pm, "vSamples", 2);
                        if (pm.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, pp, pm, "thickness", 1);
                        if (pm.get("connectSamples") != null && !(pm.get("connectSamples") instanceof Boolean)) {
                            out.add(AssemblyValidationSupport.err(pp + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                        }
                    }
                }
                if (m.get("uSamples") != null || m.get("u") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("vSamples") != null || m.get("v") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "vSamples", 2);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(AssemblyValidationSupport.err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
                if (m.get("stitch") != null && !(m.get("stitch") instanceof Boolean)) {
                    out.add(AssemblyValidationSupport.err(p + ".stitch", "E_BOOL_TYPE", "stitch 必须是布尔"));
                }
                if (m.get("stitchEpsilon") != null || m.get("stitch_eps") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "stitchEpsilon", 0);
                if (m.get("stitchSamples") != null || m.get("stitch_samples") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "stitchSamples", 2);
                if (m.get("stitchResampleMode") != null || m.get("stitch_resample_mode") != null) {
                    String mode = AssemblyValidationSupport.str(m.get("stitchResampleMode"), AssemblyValidationSupport.str(m.get("stitch_resample_mode"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!mode.isEmpty() && !(mode.equals("RESAMPLE") || mode.equals("NONE") || mode.equals("AUTO"))) {
                        out.add(AssemblyValidationSupport.warn(p + ".stitchResampleMode", "W_BEZIER_SET_RESAMPLE_MODE", "stitchResampleMode 建议 RESAMPLE/NONE/AUTO（当前=" + mode + "）"));
                    }
                }
                if (m.get("capWidth") != null || m.get("cap_width") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "capWidth", 0);
                // topology.grid or legacy grid
                Object grid = m.get("grid");
                Object topo = m.get("topology");
                if (topo instanceof Map<?, ?> tm && tm.get("grid") != null) grid = tm.get("grid");
                if (grid != null && !(grid instanceof List<?>)) {
                    out.add(AssemblyValidationSupport.err(p + ".topology.grid", "E_BEZIER_SET_GRID_TYPE", "grid 必须是二维数组"));
                }
                if (topo instanceof Map<?, ?> tm2 && tm2.get("links") != null && !(tm2.get("links") instanceof List<?>)) {
                    out.add(AssemblyValidationSupport.err(p + ".topology.links", "E_BEZIER_SET_LINKS_TYPE", "topology.links 必须是数组"));
                }
                if (topo instanceof Map<?, ?> tm3 && tm3.get("links") instanceof List<?> links) {
                    for (int li = 0; li < links.size(); li++) {
                        Object lo = links.get(li);
                        String lp = p + ".topology.links[" + li + "]";
                        if (!(lo instanceof Map<?, ?> lm)) {
                            out.add(AssemblyValidationSupport.err(lp, "E_BEZIER_SET_LINK_NOT_OBJECT", "links[] 条目必须是对象（map）"));
                            continue;
                        }
                        AssemblyValidationSupport.warnUnknownKeys(out, lp, lm, java.util.Set.of(
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
                        if (lm.get("a") == null && lm.get("from") == null) out.add(AssemblyValidationSupport.err(lp + ".a", "E_BEZIER_SET_LINK_A", "link.a/from 缺失"));
                        if (lm.get("b") == null && lm.get("to") == null) out.add(AssemblyValidationSupport.err(lp + ".b", "E_BEZIER_SET_LINK_B", "link.b/to 缺失"));
                        Object ea = lm.get("ea");
                        if (ea == null) ea = lm.get("edgeA");
                        if (ea == null) ea = lm.get("fromEdge");
                        Object eb = lm.get("eb");
                        if (eb == null) eb = lm.get("edgeB");
                        if (eb == null) eb = lm.get("toEdge");
                        if (ea == null) out.add(AssemblyValidationSupport.err(lp + ".ea", "E_BEZIER_SET_LINK_EA", "link.ea 缺失"));
                        if (eb == null) out.add(AssemblyValidationSupport.err(lp + ".eb", "E_BEZIER_SET_LINK_EB", "link.eb 缺失"));
                        if (lm.get("epsilon") != null || lm.get("stitchEpsilon") != null) AssemblyValidationSupport.requireIntMin(out, lp, lm, "epsilon", 0);
                        if (lm.get("samples") != null || lm.get("stitchSamples") != null) AssemblyValidationSupport.requireIntMin(out, lp, lm, "samples", 2);
                        if (lm.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, lp, lm, "thickness", 1);
                        if (lm.get("capWidth") != null || lm.get("cap_width") != null) AssemblyValidationSupport.requireIntMin(out, lp, lm, "capWidth", 0);

                        AssemblyValidationSupport.validateRange01(out, lp + ".aRange", lm.get("aRange"), lm.get("a_range"), lm.get("fromRange"));
                        AssemblyValidationSupport.validateRange01(out, lp + ".bRange", lm.get("bRange"), lm.get("b_range"), lm.get("toRange"));
                    }
                }
            }
            if (op.equals("SURFACE_OFFSET")) {
                if (!(m.get("source") instanceof Map<?, ?>)) {
                    out.add(AssemblyValidationSupport.err(p + ".source", "E_SURF_OFFSET_SOURCE", "SURFACE_OFFSET.source 必须是对象（map）"));
                }
                if (m.get("uSamples") != null || m.get("u") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("vSamples") != null || m.get("v") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "vSamples", 2);
                if (m.get("offset") != null || m.get("distance") != null) AssemblyValidationSupport.requireIntIfPresent(out, p, m, "offset");
                if (m.get("shellThickness") != null || m.get("shell_thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "shellThickness", 1);
                if (m.get("normalMode") != null || m.get("normal_mode") != null) {
                    String nm = AssemblyValidationSupport.str(m.get("normalMode"), AssemblyValidationSupport.str(m.get("normal_mode"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!nm.isEmpty() && !(nm.equals("DDA") || nm.equals("AXIS"))) {
                        out.add(AssemblyValidationSupport.warn(p + ".normalMode", "W_SURF_OFFSET_NORMAL_MODE", "normalMode 建议 DDA/AXIS（当前=" + nm + "）"));
                    }
                }
                if (m.get("stepLen") != null || m.get("step_len") != null || m.get("step") != null) {
                    Double sl = AssemblyValidationSupport.doubleOrNull(m.get("stepLen"));
                    if (sl == null) sl = AssemblyValidationSupport.doubleOrNull(m.get("step_len"));
                    if (sl == null) sl = AssemblyValidationSupport.doubleOrNull(m.get("step"));
                    if (sl == null) out.add(AssemblyValidationSupport.err(p + ".stepLen", "E_DOUBLE", "stepLen/step 必须是数字"));
                    else if (sl < 0.25 || sl > 4.0) out.add(AssemblyValidationSupport.warn(p + ".stepLen", "W_RANGE", "stepLen 建议在 0.25..4.0（当前=" + sl + "）"));
                }
                if (m.get("connectMaxStep") != null || m.get("connect_max_step") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "connectMaxStep", 1);
            }
            if (op.equals("IMPLICIT_FIELD")) {
                // bounds or w/d/h
                // (engine is permissive; validator keeps training stable)
                if (m.get("iso") != null && AssemblyValidationSupport.doubleOrNull(m.get("iso")) == null) out.add(AssemblyValidationSupport.err(p + ".iso", "E_DOUBLE_TYPE", "iso 必须是数字"));
                if (m.get("band") != null && AssemblyValidationSupport.doubleOrNull(m.get("band")) == null) out.add(AssemblyValidationSupport.err(p + ".band", "E_DOUBLE_TYPE", "band 必须是数字"));
            }
            if (op.equals("MARCHING_CUBES")) {
                if (m.get("iso") != null && AssemblyValidationSupport.doubleOrNull(m.get("iso")) == null) out.add(AssemblyValidationSupport.err(p + ".iso", "E_DOUBLE_TYPE", "iso 必须是数字"));
                if (m.get("fill") != null || m.get("samples") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "fill", 1);
            }
            if (op.equals("REVOLVE_SURFACE")) {
                Object profObj = m.get("profileRings");
                if (profObj == null) profObj = m.get("rings");
                if (profObj == null) profObj = m.get("profilePoints");
                if (profObj == null) profObj = m.get("points");
                if (!(profObj instanceof List<?>)) {
                    out.add(AssemblyValidationSupport.err(p, "E_REVOLVE_PROFILE_MISSING", "REVOLVE_SURFACE 需要 profilePoints/profileRings（数组）"));
                }
                if (m.get("segments") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "segments", 8);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(AssemblyValidationSupport.err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
                if (m.get("angleDeg") != null || m.get("angle") != null) {
                    Double av = AssemblyValidationSupport.doubleOrNull(m.get("angleDeg"));
                    if (av == null) av = AssemblyValidationSupport.doubleOrNull(m.get("angle"));
                    if (av == null) out.add(AssemblyValidationSupport.err(p + ".angleDeg", "E_DOUBLE_TYPE", "angleDeg/angle 必须是数字"));
                }
            }
            if (op.equals("LOFT_SURFACE")) {
                Object sec = m.get("sections");
                if (!(sec instanceof List<?> sl) || sl.size() < 2) {
                    out.add(AssemblyValidationSupport.err(p + ".sections", "E_LOFT_SECTIONS", "LOFT_SURFACE.sections 必须是数组且至少 2 段"));
                } else {
                    for (int si = 0; si < sl.size(); si++) {
                        Object it2 = sl.get(si);
                        String sp = p + ".sections[" + si + "]";
                        if (!(it2 instanceof Map<?, ?> sm)) {
                            out.add(AssemblyValidationSupport.err(sp, "E_LOFT_SECTION_NOT_OBJECT", "sections[] 条目必须是对象（map）"));
                            continue;
                        }
                        AssemblyValidationSupport.warnUnknownKeys(out, sp, sm, java.util.Set.of(
                                "at", "x", "y", "z",
                                "profilePoints", "profileRings", "rings"
                        ));
                        Object at = sm.get("at");
                        if (at != null && !(at instanceof Map<?, ?>)) out.add(AssemblyValidationSupport.err(sp + ".at", "E_LOFT_AT_TYPE", "at 必须是对象（map）"));
                        Object prof = sm.get("profileRings");
                        if (prof == null) prof = sm.get("rings");
                        if (prof == null) prof = sm.get("profilePoints");
                        if (!(prof instanceof List<?>)) out.add(AssemblyValidationSupport.err(sp, "E_LOFT_PROFILE_MISSING", "每段需要 profilePoints/profileRings（数组）"));
                    }
                }
                if (m.get("uSamples") != null || m.get("u") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "uSamples", 2);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("connectSamples") != null && !(m.get("connectSamples") instanceof Boolean)) {
                    out.add(AssemblyValidationSupport.err(p + ".connectSamples", "E_BOOL_TYPE", "connectSamples 必须是布尔"));
                }
            }
            if (op.contains("SPLINE")) {
                Object pts = m.get("points");
                if (!(pts instanceof List<?> l) || l.size() < 2) {
                    out.add(AssemblyValidationSupport.err(p + ".points", "E_SPLINE_POINTS_MIN2", "SPLINE_SWEEP.points 至少需要 2 个点"));
                } else {
                    AssemblyValidationSupport.validatePoints(out, p + ".points", l);
                }
                // profile enum sanity (optional but training helpful)
                String profile = AssemblyValidationSupport.str(m.get("profile"), "SPHERE").trim().toUpperCase(Locale.ROOT);
                boolean ok = profile.equals("SPHERE") || profile.equals("RECT") || profile.equals("POLYGON");
                if (!ok) out.add(AssemblyValidationSupport.err(p + ".profile", "E_SPLINE_PROFILE_VALUE", "SPLINE_SWEEP.profile 取值非法（SPHERE/RECT/POLYGON）: " + profile));
            }
    }
}
