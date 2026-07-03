package com.formacraft.server.assembly.validation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates volume / roof / floor-plan assembly ops. */
final class AssemblyOpVolumeValidator {
    private AssemblyOpVolumeValidator() {}

    static void validate(String op, List<AssemblyValidationIssue> out, String p, Map<?, ?> m) {

            if (op.equals("EXTRUDE_POLYGON")) {
                if (m.get("h") != null || m.get("height") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "h", 1);
                if (m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                String shape = AssemblyValidationSupport.str(m.get("shape"), "RECT").trim().toUpperCase(Locale.ROOT);
                if (!(shape.equals("RECT") || shape.equals("POINTS") || shape.equals("POLYGON"))) {
                    // Engine treats non-RECT as points; still warn loudly for training
                    out.add(AssemblyValidationSupport.warn(p + ".shape", "W_SHAPE_VALUE", "EXTRUDE_POLYGON.shape 建议使用 RECT 或 points[]"));
                }
                if (shape.equals("RECT")) {
                    AssemblyValidationSupport.requireIntMin(out, p, m, "w", 1);
                    AssemblyValidationSupport.requireIntMin(out, p, m, "d", 1);
                } else {
                    Object pts = m.get("points");
                    if (!(pts instanceof List<?> l) || l.size() < 3) {
                        out.add(AssemblyValidationSupport.err(p + ".points", "E_EXTRUDE_POINTS_MIN3", "EXTRUDE_POLYGON.points 至少需要 3 个点（每个点需含 x/z）"));
                    } else {
                        AssemblyValidationSupport.validatePointsXZ(out, p + ".points", l);
                    }
                }
            }
            if (op.equals("ROOF_COVER")) {
                String type = AssemblyValidationSupport.str(m.get("type"), "FLAT").trim().toUpperCase(Locale.ROOT);
                if (!(type.equals("FLAT") || type.equals("GABLE"))) {
                    out.add(AssemblyValidationSupport.err(p + ".type", "E_ROOF_TYPE_VALUE", "ROOF_COVER.type 取值非法（允许 FLAT/GABLE）: " + type));
                }
                if (m.get("w") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "w", 1);
                if (m.get("d") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "d", 1);
                if (m.get("y") != null && AssemblyValidationSupport.intOrNull(m.get("y")) == null) out.add(AssemblyValidationSupport.err(p + ".y", "E_INT_TYPE", "y 必须是整数"));
                if (m.get("overhang") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "overhang", 0);
                if (m.get("rise") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "rise", 1);
                // Forma-Gene integration: curvature power and corner lift
                if (m.get("curvaturePower") != null || m.get("curvature_power") != null) {
                    Object cp = m.get("curvaturePower") != null ? m.get("curvaturePower") : m.get("curvature_power");
                    if (cp instanceof Number) {
                        double v = ((Number) cp).doubleValue();
                        if (v < 0.1 || v > 3.0) {
                            out.add(AssemblyValidationSupport.warn(p + ".curvaturePower", "W_ROOF_CURVATURE_RANGE", "curvaturePower 建议在 0.1~3.0 范围内"));
                        }
                    }
                }
                if (m.get("cornerLift") != null || m.get("corner_lift") != null) {
                    Object cl = m.get("cornerLift") != null ? m.get("cornerLift") : m.get("corner_lift");
                    if (cl instanceof Number) {
                        double v = ((Number) cl).doubleValue();
                        if (v < 0.0 || v > 2.0) {
                            out.add(AssemblyValidationSupport.warn(p + ".cornerLift", "W_ROOF_CORNER_LIFT_RANGE", "cornerLift 建议在 0.0~2.0 范围内"));
                        }
                    }
                }
            }
            if (op.equals("BSP_FLOOR_PLAN")) {
                AssemblyValidationSupport.requireIntMin(out, p, m, "w", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "d", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "h", 1);
                Object cfg = m.get("floor_plan_logic");
                if (cfg == null) cfg = m.get("config");
                if (cfg == null) cfg = m.get("floorPlanLogic");
                if (cfg == null) {
                    out.add(AssemblyValidationSupport.err(p + ".config", "E_BSP_CONFIG_MISSING", "BSP_FLOOR_PLAN 需要 config/floor_plan_logic"));
                } else if (!(cfg instanceof Map<?, ?>)) {
                    out.add(AssemblyValidationSupport.err(p + ".config", "E_BSP_CONFIG_TYPE", "BSP_FLOOR_PLAN.config 必须是对象（map）"));
                }
            }

    }
}
