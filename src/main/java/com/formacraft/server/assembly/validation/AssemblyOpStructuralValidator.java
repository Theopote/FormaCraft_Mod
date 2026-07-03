package com.formacraft.server.assembly.validation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates structural / skeletal assembly ops. */
final class AssemblyOpStructuralValidator {
    private AssemblyOpStructuralValidator() {}

    static void validate(String op, List<AssemblyValidationIssue> out, String p, Map<?, ?> m) {

            if (op.equals("PUSH_ORIGIN")) {
                // dx/dy/dz optional (default 0) but should be ints if present
                if (m.get("dx") != null && AssemblyValidationSupport.intOrNull(m.get("dx")) == null) out.add(AssemblyValidationSupport.err(p + ".dx", "E_INT_TYPE", "dx 必须是整数"));
                if (m.get("dy") != null && AssemblyValidationSupport.intOrNull(m.get("dy")) == null) out.add(AssemblyValidationSupport.err(p + ".dy", "E_INT_TYPE", "dy 必须是整数"));
                if (m.get("dz") != null && AssemblyValidationSupport.intOrNull(m.get("dz")) == null) out.add(AssemblyValidationSupport.err(p + ".dz", "E_INT_TYPE", "dz 必须是整数"));
            }
            if (op.equals("POP_ORIGIN")) {
                // no required fields
            }
            if (op.equals("CLEAR_BOX")) {
                // require x0..z1 if present; allow defaults but enforce type
                AssemblyValidationSupport.requireIntIfPresent(out, p, m, "x0");
                AssemblyValidationSupport.requireIntIfPresent(out, p, m, "y0");
                AssemblyValidationSupport.requireIntIfPresent(out, p, m, "z0");
                AssemblyValidationSupport.requireIntIfPresent(out, p, m, "x1");
                AssemblyValidationSupport.requireIntIfPresent(out, p, m, "y1");
                AssemblyValidationSupport.requireIntIfPresent(out, p, m, "z1");
            }
            if (op.equals("SHELL_BOX")) {
                // Engine clamps but validator makes training stable
                AssemblyValidationSupport.requireIntMin(out, p, m, "w", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "d", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "h", 2);
                if (m.get("floorStep") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "floorStep", 1);
                // Forma-Gene integration: twist support
                if (m.get("twistTurns") != null || m.get("twist_turns") != null) {
                    Object tt = m.get("twistTurns") != null ? m.get("twistTurns") : m.get("twist_turns");
                    if (tt instanceof Number) {
                        double v = ((Number) tt).doubleValue();
                        if (v < -2.0 || v > 2.0) {
                            out.add(AssemblyValidationSupport.warn(p + ".twistTurns", "W_SHELL_BOX_TWIST_RANGE", "twistTurns 建议在 -2.0~2.0 范围内"));
                        }
                    }
                }
                if (m.get("twistPhase") != null || m.get("twist_phase") != null) {
                    Object tp = m.get("twistPhase") != null ? m.get("twistPhase") : m.get("twist_phase");
                    if (tp instanceof Number) {
                        double v = ((Number) tp).doubleValue();
                        if (v < 0.0 || v > 1.0) {
                            out.add(AssemblyValidationSupport.warn(p + ".twistPhase", "W_SHELL_BOX_TWIST_PHASE_RANGE", "twistPhase 建议在 0.0~1.0 范围内"));
                        }
                    }
                }
            }
            if (op.equals("CYLINDER")) {
                if (m.get("r") != null || m.get("radius") != null) {
                    Integer rv = AssemblyValidationSupport.intOrNull(m.get("r"));
                    if (rv == null) rv = AssemblyValidationSupport.intOrNull(m.get("radius"));
                    if (rv == null) out.add(AssemblyValidationSupport.err(p + ".r", "E_INT_TYPE", "r/radius 必须是整数"));
                    else if (rv < 2) out.add(AssemblyValidationSupport.err(p + ".r", "E_INT_RANGE", "r/radius 必须 >= 2"));
                }
                if (m.get("h") != null || m.get("height") != null) {
                    Integer hv = AssemblyValidationSupport.intOrNull(m.get("h"));
                    if (hv == null) hv = AssemblyValidationSupport.intOrNull(m.get("height"));
                    if (hv == null) out.add(AssemblyValidationSupport.err(p + ".h", "E_INT_TYPE", "h/height 必须是整数"));
                    else if (hv < 1) out.add(AssemblyValidationSupport.err(p + ".h", "E_INT_RANGE", "h/height 必须 >= 1"));
                }
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
            }
            if (op.equals("CONNECTOR_LINE")) {
                // require endpoints
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".from", m.get("from"), "E_CONN_FROM_MISSING");
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".to", m.get("to"), "E_CONN_TO_MISSING");
                // or allow x0..z1 form (if from/to omitted)
                if (m.get("from") == null) {
                    AssemblyValidationSupport.requireIntIfPresent(out, p, m, "x0");
                    AssemblyValidationSupport.requireIntIfPresent(out, p, m, "y0");
                    AssemblyValidationSupport.requireIntIfPresent(out, p, m, "z0");
                }
                if (m.get("to") == null) {
                    AssemblyValidationSupport.requireIntIfPresent(out, p, m, "x1");
                    AssemblyValidationSupport.requireIntIfPresent(out, p, m, "y1");
                    AssemblyValidationSupport.requireIntIfPresent(out, p, m, "z1");
                }
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("h") != null || m.get("height") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "h", 1);
            }
            if (op.equals("TRUSS_2D")) {
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".from", m.get("from"), "E_TRUSS_FROM_MISSING");
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".to", m.get("to"), "E_TRUSS_TO_MISSING");
                if (m.get("height") != null || m.get("h") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "height", 1);
                if (m.get("module") != null || m.get("step") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "module", 1);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("pattern") != null) {
                    String pat = String.valueOf(m.get("pattern")).trim().toUpperCase(Locale.ROOT);
                    if (!pat.isEmpty() && !(pat.contains("WARREN") || pat.contains("PRATT") || pat.contains("HOWE"))) {
                        out.add(AssemblyValidationSupport.warn(p + ".pattern", "W_TRUSS_PATTERN_VALUE", "TRUSS_2D.pattern 建议使用 WARREN/PRATT/HOWE（当前=" + pat + "）"));
                    }
                }
            }
            if (op.equals("ARCH_RIB")) {
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".from", m.get("from"), "E_ARCH_FROM_MISSING");
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".to", m.get("to"), "E_ARCH_TO_MISSING");
                if (m.get("rise") != null || m.get("sagitta") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "rise", 1);
                if (m.get("samples") != null || m.get("steps") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "samples", 2);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
            }
            if (op.equals("BUTTRESS")) {
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".from", m.get("from"), "E_BUTTRESS_FROM_MISSING");
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".to", m.get("to"), "E_BUTTRESS_TO_MISSING");
                if (m.get("rise") != null || m.get("sagitta") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "rise", 1);
                if (m.get("samples") != null || m.get("steps") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "samples", 2);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("pierDown") != null || m.get("pier_down") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "pierDown", 0);
            }
            if (op.equals("TENSION_CABLE")) {
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".from", m.get("from"), "E_CABLE_FROM_MISSING");
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".to", m.get("to"), "E_CABLE_TO_MISSING");
                if (m.get("sag") != null || m.get("droop") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "sag", 1);
                if (m.get("samples") != null || m.get("steps") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "samples", 2);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("hangersEvery") != null || m.get("hangerEvery") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "hangersEvery", 1);
                if (m.get("hangersToY") != null || m.get("hangerToY") != null) {
                    if (AssemblyValidationSupport.intOrNull(m.get("hangersToY")) == null && AssemblyValidationSupport.intOrNull(m.get("hangerToY")) == null) {
                        out.add(AssemblyValidationSupport.err(p + ".hangersToY", "E_INT_TYPE", "hangersToY 必须是整数"));
                    }
                }
                if (m.get("cableCount") != null || m.get("count") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "cableCount", 1);
                if (m.get("cableSpacing") != null || m.get("spacing") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "cableSpacing", 1);
                if (m.get("cableAxis") != null) {
                    String ax = String.valueOf(m.get("cableAxis")).trim().toUpperCase(Locale.ROOT);
                    if (!ax.isEmpty() && !(ax.equals("AUTO") || ax.equals("X") || ax.equals("Z"))) {
                        out.add(AssemblyValidationSupport.warn(p + ".cableAxis", "W_CABLE_AXIS_VALUE", "cableAxis 建议使用 AUTO/X/Z（当前=" + m.get("cableAxis") + "）"));
                    }
                }
            }
            if (op.equals("ANCHOR_FOOTPRINT")) {
                if (m.get("x0") == null || m.get("x1") == null || m.get("z0") == null || m.get("z1") == null) {
                    out.add(AssemblyValidationSupport.err(p, "E_ANCHOR_FOOTPRINT_BOUNDS", "ANCHOR_FOOTPRINT 需要 x0/x1/z0/z1"));
                }
                if (m.get("yBase") != null && AssemblyValidationSupport.intOrNull(m.get("yBase")) == null) {
                    out.add(AssemblyValidationSupport.err(p + ".yBase", "E_INT_TYPE", "yBase 必须是整数"));
                }
                if (m.get("maxDepth") != null || m.get("anchorDepth") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "maxDepth", 0);
            }
            if (op.equals("ANCHORAGE")) {
                if (m.get("w") != null || m.get("width") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "w", 1);
                if (m.get("d") != null || m.get("depth") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "d", 1);
                if (m.get("h") != null || m.get("height") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "h", 1);
                if (m.get("yBase") != null && AssemblyValidationSupport.intOrNull(m.get("yBase")) == null) {
                    out.add(AssemblyValidationSupport.err(p + ".yBase", "E_INT_TYPE", "yBase 必须是整数"));
                }
                if (m.get("maxDepth") != null || m.get("anchorDepth") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "maxDepth", 0);
                if (m.get("topBevel") != null || m.get("top_bevel") != null || m.get("bevel") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "topBevel", 0);
                if (m.get("guardWallHeight") != null || m.get("guard_wall_height") != null || m.get("parapetHeight") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "guardWallHeight", 0);
                if (m.get("guardWallInset") != null || m.get("guard_wall_inset") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "guardWallInset", 0);
                Object holes = m.get("holes");
                if (holes == null) holes = m.get("cableHoles");
                if (holes != null && !(holes instanceof List<?>)) {
                    out.add(AssemblyValidationSupport.err(p + ".holes", "E_HOLES_TYPE", "holes/cableHoles 必须是数组"));
                }
                if (holes instanceof List<?> list) {
                    for (int hi = 0; hi < list.size(); hi++) {
                        Object holeIt = list.get(hi);
                        String hp = p + ".holes[" + hi + "]";
                        if (!(holeIt instanceof Map<?, ?> hm)) {
                            out.add(AssemblyValidationSupport.err(hp, "E_HOLE_NOT_OBJECT", "hole 必须是对象"));
                            continue;
                        }
                        Object face = hm.get("face");
                        if (face != null) {
                            String s = String.valueOf(face).trim().toUpperCase(Locale.ROOT);
                            if (!(s.equals("NORTH") || s.equals("SOUTH") || s.equals("EAST") || s.equals("WEST"))) {
                                out.add(AssemblyValidationSupport.warn(hp + ".face", "W_HOLE_FACE_VALUE", "hole.face 建议使用 NORTH/SOUTH/EAST/WEST（当前=" + face + "）"));
                            }
                        } else {
                            out.add(AssemblyValidationSupport.warn(hp + ".face", "W_HOLE_FACE_MISSING", "hole 建议提供 face（NORTH/SOUTH/EAST/WEST）"));
                        }
                        if (hm.get("r") != null || hm.get("radius") != null) AssemblyValidationSupport.requireIntMin(out, hp, hm, "r", 1);
                        if (hm.get("len") != null || hm.get("length") != null) AssemblyValidationSupport.requireIntMin(out, hp, hm, "len", 1);
                        if (hm.get("y") != null && AssemblyValidationSupport.intOrNull(hm.get("y")) == null) out.add(AssemblyValidationSupport.err(hp + ".y", "E_INT_TYPE", "hole.y 必须是整数"));
                        if (hm.get("x") != null && AssemblyValidationSupport.intOrNull(hm.get("x")) == null) out.add(AssemblyValidationSupport.err(hp + ".x", "E_INT_TYPE", "hole.x 必须是整数"));
                        if (hm.get("z") != null && AssemblyValidationSupport.intOrNull(hm.get("z")) == null) out.add(AssemblyValidationSupport.err(hp + ".z", "E_INT_TYPE", "hole.z 必须是整数"));
                    }
                }
            }
            if (op.equals("FRAME_GRID_3D")) {
                // bounds required
                if (m.get("x0") == null || m.get("x1") == null || m.get("y0") == null || m.get("y1") == null || m.get("z0") == null || m.get("z1") == null) {
                    out.add(AssemblyValidationSupport.err(p, "E_FRAMEGRID_BOUNDS", "FRAME_GRID_3D 需要 x0/x1/y0/y1/z0/z1"));
                }
                if (m.get("stepX") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "stepX", 1);
                if (m.get("stepY") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "stepY", 1);
                if (m.get("stepZ") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "stepZ", 1);
                if (m.get("step") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "step", 1);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                if (m.get("mode") != null) {
                    String s = String.valueOf(m.get("mode")).trim().toUpperCase(Locale.ROOT);
                    if (!s.isEmpty() && !(s.equals("SURFACE") || s.equals("ALL"))) {
                        out.add(AssemblyValidationSupport.warn(p + ".mode", "W_FRAMEGRID_MODE_VALUE", "mode 建议使用 SURFACE/ALL（当前=" + m.get("mode") + "）"));
                    }
                }
                if (m.get("diagonal") != null) {
                    String s = String.valueOf(m.get("diagonal")).trim().toUpperCase(Locale.ROOT);
                    if (!s.isEmpty() && !(s.equals("NONE") || s.equals("FACE") || s.equals("SPACE"))) {
                        out.add(AssemblyValidationSupport.warn(p + ".diagonal", "W_FRAMEGRID_DIAGONAL_VALUE", "diagonal 建议使用 NONE/FACE/SPACE（当前=" + m.get("diagonal") + "）"));
                    }
                }
            }
            if (op.equals("STAIR_SYSTEM")) {
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".from", m.get("from"), "E_STAIR_FROM_MISSING");
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".to", m.get("to"), "E_STAIR_TO_MISSING");
                if (m.get("width") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "width", 1);
                if (m.get("clearHeight") != null || m.get("clear_h") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "clearHeight", 0);
            }
    }
}
