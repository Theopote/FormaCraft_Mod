package com.formacraft.ai.prompt;

import com.formacraft.server.assembly.schema.AssemblyComponentSchemaRegistry;
import com.formacraft.server.assembly.schema.AssemblySchemaExporter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI prompt view of MetaAssembly schema (loaded from {@link AssemblySchemaExporter} snapshot).
 */
public final class AssemblySchemaCatalog {

    public record ComponentSchema(
            String type,
            String category,
            List<String> requiredParams,
            List<String> optionalParams,
            List<String> ports
    ) {}

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

    public static List<ComponentSchema> allComponents() {
        List<ComponentSchema> out = new ArrayList<>();
        for (AssemblySchemaExporter.ExportedComponent c : AssemblySchemaExporter.snapshot().components()) {
            out.add(new ComponentSchema(
                    c.type(),
                    c.category(),
                    c.requiredParams(),
                    c.optionalParams(),
                    c.ports()
            ));
        }
        return out;
    }

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
        for (ComponentSchema s : allComponents()) {
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
If geometry is unsupported, return plan_status="capability_gap" with capability_gap{code,message,path,suggestions} — never empty ASSEMBLY.

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
        sb.append("\nPreset shorthand (preferred):\n");
        for (AssemblySchemaExporter.ExportedPreset preset : AssemblySchemaExporter.snapshot().presets()) {
            sb.append("  ").append(preset.id()).append(": presetParams ")
                    .append(String.join(", ", preset.parameters())).append("\n");
        }
        sb.append("""
Do NOT invent port names; use listed ports only.

Compatibility:
""");
        for (String rule : AssemblySchemaExporter.snapshot().compatibilityRules()) {
            sb.append("- ").append(rule).append("\n");
        }
        return sb.toString();
    }

    public static boolean isKnownComponentType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String upper = type.trim().toUpperCase(Locale.ROOT);
        for (ComponentSchema s : allComponents()) {
            if (upper.equals(s.type()) || upper.contains(s.type())) {
                return true;
            }
        }
        return AssemblyComponentSchemaRegistry.isKnownType(type);
    }

    public static Set<String> knownPresetIds() {
        Set<String> out = new LinkedHashSet<>();
        for (AssemblySchemaExporter.ExportedPreset p : AssemblySchemaExporter.snapshot().presets()) {
            out.add(p.id());
        }
        return out;
    }

    private static ComponentSchema schemaFor(String type) {
        for (ComponentSchema s : allComponents()) {
            if (s.type().equals(type)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown schema type: " + type);
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
