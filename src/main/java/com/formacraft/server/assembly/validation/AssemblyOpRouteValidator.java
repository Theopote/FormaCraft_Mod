package com.formacraft.server.assembly.validation;

import java.util.List;
import java.util.Map;

/** Validates terrain-following route assembly ops. */
final class AssemblyOpRouteValidator {
    private AssemblyOpRouteValidator() {}

    static void validate(String op, List<AssemblyValidationIssue> out, String p, Map<?, ?> m) {

            if (op.equals("PATH_ROUTE") || op.equals("WALL_ROUTE") || op.equals("BRIDGE_ROUTE")) {
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".from", m.get("from"), "E_ROUTE_FROM_MISSING");
                AssemblyValidationSupport.validatePointRefXYZ(out, p + ".to", m.get("to"), "E_ROUTE_TO_MISSING");
                if (m.get("terrainAdaptation") != null) {
                    AssemblyValidationSupport.validateTerrainAdaptation(out, p + ".terrainAdaptation", m.get("terrainAdaptation"), op);
                }
                if (op.equals("PATH_ROUTE")) {
                    if (m.get("width") != null || m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "width", 1);
                }
                if (op.equals("WALL_ROUTE")) {
                    if (m.get("wallHeight") != null || m.get("height") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "wallHeight", 1);
                    if (m.get("wallThickness") != null || m.get("thickness") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "wallThickness", 1);
                    if (m.get("foundationDepth") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "foundationDepth", 0);
                    if (m.get("maxStep") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "maxStep", 0);
                }
                if (op.equals("BRIDGE_ROUTE")) {
                    if (m.get("width") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "width", 1);
                }
            }
    }
}
