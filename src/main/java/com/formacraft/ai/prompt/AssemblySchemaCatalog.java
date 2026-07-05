package com.formacraft.ai.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compact MetaAssembly component schema for AI prompts (Nodecraft-style intent cropping).
 */
public final class AssemblySchemaCatalog {

    public record ComponentSchema(
            String type,
            String category,
            List<String> requiredParams,
            List<String> optionalParams,
            List<String> ports
    ) {}

    private static final List<String> BUILTIN_PORTS = List.of(
            "center", "bottom_center", "top_center",
            "north", "south", "east", "west",
            "nw", "ne", "sw", "se",
            "entrance", "exit", "in", "out"
    );

    private static final List<ComponentSchema> ALL = List.of(
            schema("SHELL_BOX", "shell", List.of("w", "d", "h"),
                    List.of("twistTurns", "twistPhase", "wall", "window", "floor", "facade"),
                    BUILTIN_PORTS),
            schema("CYLINDER", "solid", List.of("r", "h"),
                    List.of("thickness", "hollow", "wall", "window"),
                    concat(BUILTIN_PORTS, List.of("ne", "nw", "se", "sw"))),
            schema("FRAME_GRID_3D", "structure", List.of("w", "d", "h"),
                    List.of("x0", "x1", "y0", "y1", "z0", "z1", "diag", "material"),
                    BUILTIN_PORTS),
            schema("SPLINE_SWEEP", "curve", List.of("points"),
                    List.of("profileW", "profileH", "thickness", "hollow", "twistTurns"),
                    List.of("start", "end", "start_n", "start_s", "start_e", "start_w",
                            "end_n", "end_s", "end_e", "end_w", "center")),
            schema("BEZIER_SURFACE", "surface", List.of("p00", "p10", "p01", "p11"),
                    List.of("samplesU", "samplesV", "thickness", "material"),
                    BUILTIN_PORTS),
            schema("LOFT_SURFACE", "surface", List.of("profiles"),
                    List.of("samples", "material"),
                    BUILTIN_PORTS),
            schema("IMPLICIT_FIELD", "field", List.of("kind"),
                    List.of("radius", "expression", "material"),
                    BUILTIN_PORTS),
            schema("MARCHING_CUBES", "field", List.of("field"),
                    List.of("resolution", "isoLevel", "material"),
                    BUILTIN_PORTS),
            schema("EXTRUDE_POLYGON", "solid", List.of("h"),
                    List.of("points", "w", "d", "shape", "thickness"),
                    BUILTIN_PORTS),
            schema("ROOF_COVER", "roof", List.of("w", "d"),
                    List.of("roofType", "h", "material"),
                    BUILTIN_PORTS),
            schema("TENSION_CABLE", "bridge", List.of("from", "to"),
                    List.of("sag", "samples", "thickness", "material"),
                    BUILTIN_PORTS),
            schema("ARCH_RIB", "structure", List.of("span", "rise"),
                    List.of("samples", "thickness", "material"),
                    BUILTIN_PORTS),
            schema("STAIR_SYSTEM", "circulation", List.of("w", "d", "h"),
                    List.of("steps", "material"),
                    BUILTIN_PORTS),
            schema("CONNECTOR_LINE", "route", List.of("from", "to"),
                    List.of("width", "material", "routing"),
                    BUILTIN_PORTS)
    );

    private static final List<String> SPIRAL_INTENT = List.of(
            "螺旋", "spiral", "helix", "twist", "瞭望", "watchtower", "lookout", "扭转"
    );
    private static final List<String> BRIDGE_INTENT = List.of(
            "桥", "bridge", "悬索", "suspension", "cable", "缆"
    );
    private static final List<String> SURFACE_INTENT = List.of(
            "曲面", "surface", "bezier", "loft", "壳", "shell", "自由几何", "freeform"
    );
    private static final List<String> FIELD_INTENT = List.of(
            "隐式", "implicit", "marching", "isosurface", "场"
    );

    private AssemblySchemaCatalog() {}

    public static List<ComponentSchema> selectRelevant(String userIntent) {
        if (userIntent == null || userIntent.isBlank()) {
            return List.of(schemaFor("SHELL_BOX"), schemaFor("CONNECTOR_LINE"));
        }
        String lower = userIntent.toLowerCase(Locale.ROOT);
        Set<String> types = new LinkedHashSet<>();
        types.add("SHELL_BOX");
        types.add("CONNECTOR_LINE");
        if (matches(lower, SPIRAL_INTENT)) {
            types.add("FRAME_GRID_3D");
            types.add("CYLINDER");
        }
        if (matches(lower, BRIDGE_INTENT)) {
            types.add("TENSION_CABLE");
            types.add("ARCH_RIB");
            types.add("SPLINE_SWEEP");
        }
        if (matches(lower, SURFACE_INTENT)) {
            types.add("BEZIER_SURFACE");
            types.add("LOFT_SURFACE");
            types.add("EXTRUDE_POLYGON");
        }
        if (matches(lower, FIELD_INTENT)) {
            types.add("IMPLICIT_FIELD");
            types.add("MARCHING_CUBES");
        }
        if (AssemblyIntentSections.detectsFreeformAssemblyIntent(userIntent)) {
            types.add("FRAME_GRID_3D");
            types.add("ROOF_COVER");
        }
        List<ComponentSchema> out = new ArrayList<>();
        for (ComponentSchema s : ALL) {
            if (types.contains(s.type())) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(ComponentSchema::type));
        return out;
    }

    public static String promptBlockForIntent(String userIntent) {
        if (!AssemblyIntentSections.detectsFreeformAssemblyIntent(userIntent)
                && (userIntent == null || selectRelevant(userIntent).size() <= 2)) {
            return "";
        }
        List<ComponentSchema> schemas = selectRelevant(userIntent);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("""

ASSEMBLY COMPONENT LIBRARY (use exact type names and port ids):
graph.components[] = { id, type, at{x,y,z}, params... }
graph.connections[] = { from: "A.port", to: "B.port", type?: "CONNECTOR_LINE"|"PATH_ROUTE"|"WALL_ROUTE" }
Ports are ALWAYS "ComponentId.portName" (e.g. "Tower.top_center" -> "Bridge.start").

""");
        for (ComponentSchema s : schemas) {
            sb.append("- ").append(s.type()).append(" [").append(s.category()).append("]");
            if (!s.requiredParams().isEmpty()) {
                sb.append(" required: ").append(String.join(", ", s.requiredParams()));
            }
            if (!s.optionalParams().isEmpty()) {
                sb.append(" optional: ").append(String.join(", ", s.optionalParams()));
            }
            sb.append("\n  ports: ").append(String.join(", ", s.ports())).append("\n");
        }
        sb.append("""
Preset shorthand (preferred for spiral towers):
  params.assembly = { "preset": "spiral_watchtower", "presetParams": { "height": 28, "footprint": 10, "twistTurns": 0.75, "styleId": "Gothic_Cathedral" } }
Do NOT invent port names; use listed ports only.

""");
        return sb.toString();
    }

    public static boolean isKnownComponentType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String upper = type.trim().toUpperCase(Locale.ROOT);
        for (ComponentSchema s : ALL) {
            if (upper.equals(s.type()) || upper.contains(s.type())) {
                return true;
            }
        }
        return com.formacraft.server.assembly.AssemblyComponentTypes.isKnown(type);
    }

    private static ComponentSchema schemaFor(String type) {
        for (ComponentSchema s : ALL) {
            if (s.type().equals(type)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown schema type: " + type);
    }

    private static ComponentSchema schema(
            String type,
            String category,
            List<String> required,
            List<String> optional,
            List<String> ports
    ) {
        return new ComponentSchema(type, category, required, optional, ports);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static boolean matches(String lower, List<String> markers) {
        for (String m : markers) {
            if (lower.contains(m.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
