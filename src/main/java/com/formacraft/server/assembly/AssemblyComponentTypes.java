package com.formacraft.server.assembly;

import java.util.Locale;
import java.util.Set;

/** Known MetaAssembly graph component type ids (for validation and AI schema). */
public final class AssemblyComponentTypes {

    public static final Set<String> KNOWN = Set.of(
            "SHELL_BOX", "BOX_SHELL",
            "CYLINDER",
            "FRAME_GRID_3D", "FRAMEGRID_3D", "SPACE_FRAME", "EXOSKELETON",
            "SPLINE_SWEEP", "SWEEP_SPLINE", "SPLINE_TUBE", "SPLINE",
            "BEZIER_SURFACE", "BEZIER_PATCH", "BEZIER", "BEZIER_SURFACE_SET",
            "LOFT_SURFACE", "LOFT", "REVOLVE_SURFACE",
            "IMPLICIT_FIELD", "IMPLICIT", "MARCHING_CUBES", "MARCHING",
            "EXTRUDE_POLYGON", "EXTRUDE",
            "ROOF_COVER", "ROOF",
            "TENSION_CABLE", "CABLE", "SAG_CABLE",
            "ARCH_RIB", "ARCH", "TRUSS_2D", "TRUSS",
            "STAIR_SYSTEM", "STAIRCASE",
            "BUTTRESS", "FLYING_BUTTRESS",
            "ANCHOR_FOOTPRINT", "ANCHORAGE", "CONNECTOR_LINE", "CONNECTOR",
            "BSP_FLOOR_PLAN", "CLEAR_BOX"
    );

    private AssemblyComponentTypes() {}

    public static boolean isKnown(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String upper = type.trim().toUpperCase(Locale.ROOT);
        if (KNOWN.contains(upper)) {
            return true;
        }
        for (String k : KNOWN) {
            if (upper.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
