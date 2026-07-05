package com.formacraft.server.assembly.schema;

import com.formacraft.server.assembly.AssemblyPortResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Canonical MetaAssembly graph component schemas (single source for exporter + AI catalog).
 */
public final class AssemblyComponentSchemaRegistry {

    public record ComponentSchemaDef(
            String type,
            String category,
            List<String> aliases,
            List<String> requiredParams,
            List<String> optionalParams
    ) {}

    private static final List<ComponentSchemaDef> DEFINITIONS = List.of(
            def("SHELL_BOX", "shell", List.of("BOX_SHELL"),
                    List.of("w", "d", "h"),
                    List.of("twistTurns", "twistPhase", "wall", "window", "floor", "roof", "facade", "floorStep")),
            def("CYLINDER", "solid", List.of(),
                    List.of("r", "h"),
                    List.of("radius", "height", "thickness", "hollow", "wall", "material")),
            def("FRAME_GRID_3D", "structure", List.of("FRAMEGRID_3D", "SPACE_FRAME", "EXOSKELETON"),
                    List.of("w", "d", "h"),
                    List.of("x0", "x1", "y0", "y1", "z0", "z1", "stepX", "stepY", "stepZ", "step", "thickness", "mode", "diagonal", "material")),
            def("SPLINE_SWEEP", "curve", List.of("SWEEP_SPLINE", "SPLINE_TUBE", "SPLINE"),
                    List.of("points"),
                    List.of("profileW", "profileH", "profile", "twistTurns", "twistPhase", "thickness", "hollow", "material", "wall")),
            def("BEZIER_SURFACE", "surface", List.of("BEZIER_PATCH", "BEZIER"),
                    List.of("points"),
                    List.of("p00", "p10", "p01", "p11", "uSamples", "vSamples", "thickness", "material")),
            def("BEZIER_SURFACE_SET", "surface", List.of("BEZIER_PATCH_SET", "BEZIER_SET"),
                    List.of("patches"),
                    List.of("topology", "uSamples", "vSamples", "thickness", "stitch", "material")),
            def("LOFT_SURFACE", "surface", List.of("LOFT", "SKIN_SURFACE"),
                    List.of("sections"),
                    List.of("uSamples", "thickness", "material")),
            def("REVOLVE_SURFACE", "surface", List.of("REVOLVE", "SURFACE_OF_REVOLUTION"),
                    List.of("profilePoints"),
                    List.of("segments", "angleDeg", "thickness", "material")),
            def("IMPLICIT_FIELD", "field", List.of("IMPLICIT"),
                    List.of("kind"),
                    List.of("radius", "expression", "material")),
            def("MARCHING_CUBES", "field", List.of("MARCHING"),
                    List.of("field"),
                    List.of("resolution", "isoLevel", "material")),
            def("EXTRUDE_POLYGON", "solid", List.of("EXTRUDE"),
                    List.of("h"),
                    List.of("points", "shape", "w", "d", "thickness", "material")),
            def("ROOF_COVER", "roof", List.of("ROOF"),
                    List.of("w", "d"),
                    List.of("roofType", "h", "material")),
            def("TENSION_CABLE", "bridge", List.of("CABLE", "SAG_CABLE"),
                    List.of("from", "to"),
                    List.of("sag", "samples", "thickness", "material", "cableCount", "cableSpacing")),
            def("ARCH_RIB", "structure", List.of("ARCH", "RIB_ARCH"),
                    List.of("span", "rise"),
                    List.of("from", "to", "samples", "thickness", "material")),
            def("TRUSS_2D", "structure", List.of("TRUSS", "TRUSS2D"),
                    List.of("from", "to"),
                    List.of("height", "module", "pattern", "thickness", "chord", "web", "material")),
            def("STAIR_SYSTEM", "circulation", List.of("STAIRS_SYSTEM", "STAIRCASE"),
                    List.of("from", "to"),
                    List.of("width", "clearHeight", "carve", "support", "stairs", "floor", "material")),
            def("BUTTRESS", "structure", List.of("FLYING_BUTTRESS"),
                    List.of("from", "to"),
                    List.of("width", "thickness", "material")),
            def("CONNECTOR_LINE", "route", List.of("CONNECTOR"),
                    List.of("from", "to"),
                    List.of("width", "thickness", "material", "routing")),
            def("ANCHOR_FOOTPRINT", "foundation", List.of("FOOTPRINT_ANCHOR"),
                    List.of("x0", "x1", "z0", "z1"),
                    List.of("yBase", "maxDepth", "material")),
            def("ANCHORAGE", "foundation", List.of("ANCHORAGE_BLOCK"),
                    List.of("w", "d", "h"),
                    List.of("yBase", "maxDepth", "solid", "material", "holes")),
            def("BSP_FLOOR_PLAN", "interior", List.of("BSP_INTERIOR"),
                    List.of("w", "d", "h"),
                    List.of("config", "coreWall", "roomWall", "stairs")),
            def("CLEAR_BOX", "utility", List.of("CARVE_BOX"),
                    List.of("x0", "x1", "y0", "y1", "z0", "z1"),
                    List.of())
    );

    private AssemblyComponentSchemaRegistry() {}

    public static List<ComponentSchemaDef> definitions() {
        return DEFINITIONS;
    }

    public static ComponentSchemaDef findByType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String upper = type.trim().toUpperCase(Locale.ROOT);
        for (ComponentSchemaDef def : DEFINITIONS) {
            if (upper.equals(def.type()) || def.aliases().contains(upper)) {
                return def;
            }
        }
        return null;
    }

    public static boolean isKnownType(String type) {
        return findByType(type) != null || com.formacraft.server.assembly.AssemblyComponentTypes.isKnown(type);
    }

    public static List<String> resolvePorts(ComponentSchemaDef def) {
        Map<String, Object> sample = sampleComponent(def);
        Set<String> ports = new TreeSet<>(AssemblyPortResolver.portIds(sample));
        return List.copyOf(ports);
    }

    public static Map<String, Object> sampleComponent(ComponentSchemaDef def) {
        Map<String, Object> c = new HashMap<>();
        c.put("type", def.type());
        c.put("w", 12);
        c.put("d", 10);
        c.put("h", 20);
        c.put("r", 6);
        c.put("span", 24);
        c.put("rise", 8);
        if ("SPLINE_SWEEP".equals(def.type())) {
            c.put("points", List.of(
                    point(0, 0, 0),
                    point(0, 10, 24)
            ));
        }
        if ("BEZIER_SURFACE".equals(def.type())) {
            c.put("points", List.of(
                    point(0, 0, 0), point(8, 0, 0), point(0, 0, 8), point(8, 0, 8),
                    point(0, 8, 0), point(8, 8, 0), point(0, 8, 8), point(8, 8, 8),
                    point(0, 0, 8), point(8, 0, 8), point(0, 8, 8), point(8, 8, 8),
                    point(0, 0, 16), point(8, 0, 16), point(0, 8, 16), point(8, 8, 16)
            ));
        }
        if ("LOFT_SURFACE".equals(def.type())) {
            c.put("sections", List.of(
                    List.of(point(-4, 0, -4), point(4, 0, -4), point(4, 0, 4), point(-4, 0, 4)),
                    List.of(point(-2, 8, -2), point(2, 8, -2), point(2, 8, 2), point(-2, 8, 2))
            ));
        }
        if ("REVOLVE_SURFACE".equals(def.type())) {
            c.put("profilePoints", List.of(point(4, 0, 0), point(2, 8, 0), point(5, 16, 0)));
        }
        if ("EXTRUDE_POLYGON".equals(def.type())) {
            c.put("points", List.of(point(-4, 0, -4), point(4, 0, -4), point(4, 0, 4), point(-4, 0, 4)));
        }
        if ("IMPLICIT_FIELD".equals(def.type())) {
            c.put("kind", "sphere");
            c.put("radius", 8);
        }
        if ("MARCHING_CUBES".equals(def.type())) {
            c.put("field", Map.of("kind", "sphere", "radius", 8));
        }
        if ("BEZIER_SURFACE_SET".equals(def.type())) {
            c.put("patches", List.of(Map.of("points", List.of(point(0, 0, 0)))));
        }
        if ("FRAME_GRID_3D".equals(def.type())) {
            c.put("x0", -6);
            c.put("x1", 6);
            c.put("y0", 0);
            c.put("y1", 20);
            c.put("z0", -5);
            c.put("z1", 5);
        }
        if ("BSP_FLOOR_PLAN".equals(def.type())) {
            c.put("config", Map.of("rooms", 4));
        }
        if ("CLEAR_BOX".equals(def.type())) {
            c.put("x0", -2);
            c.put("x1", 2);
            c.put("y0", 0);
            c.put("y1", 8);
            c.put("z0", -2);
            c.put("z1", 2);
        }
        if ("ANCHOR_FOOTPRINT".equals(def.type())) {
            c.put("x0", -6);
            c.put("x1", 6);
            c.put("z0", -5);
            c.put("z1", 5);
        }
        return c;
    }

    public static List<String> engineOps() {
        return List.of(
                "PUSH_ORIGIN", "POP_ORIGIN", "CLEAR_BOX",
                "ANCHOR_FOOTPRINT", "ANCHORAGE",
                "SHELL_BOX", "CYLINDER", "CONNECTOR_LINE",
                "TRUSS_2D", "ARCH_RIB", "BUTTRESS", "TENSION_CABLE",
                "FRAME_GRID_3D", "STAIR_SYSTEM",
                "BEZIER_SURFACE", "BEZIER_SURFACE_SET", "SURFACE_OFFSET",
                "IMPLICIT_FIELD", "MARCHING_CUBES", "REVOLVE_SURFACE", "LOFT_SURFACE",
                "SPLINE_SWEEP", "SPLINE_TUBE",
                "PATH_ROUTE", "WALL_ROUTE", "BRIDGE_ROUTE",
                "EXTRUDE_POLYGON", "ROOF_COVER", "BSP_FLOOR_PLAN",
                "SURFACE_PATTERN", "FACADE_GRID", "SURFACE_BANDS", "OPENINGS"
        );
    }

    public static List<String> compatibilityRules() {
        return List.of(
                "Use component_type=ASSEMBLY at top level; never nest params.assembly inside MASS_*.",
                "Prefer preset shorthand when intent matches: spiral_watchtower, suspension_bridge_simple, gothic_shell_box.",
                "graph.connections endpoints must be \"ComponentId.port\" using exported port ids only.",
                "For conventional buildings use MASS_* + ROOF; use ASSEMBLY preset/graph/ops for freeform geometry.",
                "If geometry is unsupported, return plan_status=capability_gap with capability_gap.code instead of empty components.",
                "Do NOT invent component types or port names outside this schema export."
        );
    }

    private static ComponentSchemaDef def(
            String type,
            String category,
            List<String> aliases,
            List<String> required,
            List<String> optional
    ) {
        return new ComponentSchemaDef(type, category, List.copyOf(aliases), List.copyOf(required), List.copyOf(optional));
    }

    private static Map<String, Object> point(int x, int y, int z) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", x);
        p.put("y", y);
        p.put("z", z);
        return p;
    }
}
