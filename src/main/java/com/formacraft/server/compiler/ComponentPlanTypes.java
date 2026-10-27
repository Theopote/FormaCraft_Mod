package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.common.llm.dto.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared component-type aliases, predicates, and param key helpers for plan compilation.
 * <p>
 * Extracted from {@link ComponentPlanCompiler} (PR0) so later align/infer/emit passes can share
 * the same normalization rules without depending on the orchestrator.
 */
final class ComponentPlanTypes {
    private ComponentPlanTypes() {}

    static final Map<String, String> COMPONENT_TYPE_ALIASES = Map.of(
            "MAIN_MASS", "MASS_MAIN",
            "BUTTRESS", "WALL",
            "WINDOW", "FACADE_WINDOWS",
            "WINDOWS", "FACADE_WINDOWS",
            "RAMPART", "WALL",
            "PALISADE", "WALL",
            "BARRIER", "WALL",
            "PARAPET", "WALL"
    );

    static final Set<String> AUTO_INFERRED_TYPES = Set.of(
            "FACADE_WINDOWS",
            "ENTRANCE",
            "ROOF",
            "ROOF_STRUCTURE",
            "CROWN",
            "CUPOLA",
            "DOME"
    );

    static String slotKey(Component c) {
        return c.slotId() != null ? c.slotId() : "__global__";
    }

    static String normalizeType(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase();
    }

    static String normalizeComponentType(String value, boolean allowUnknown) {
        String type = normalizeType(value);
        if (type.isBlank()) {
            return "";
        }
        String alias = COMPONENT_TYPE_ALIASES.get(type);
        if (alias != null) {
            type = alias;
        }
        if (allowUnknown) {
            return type;
        }
        if (ComponentGeneratorRegistry.hasGenerator(type)) {
            return type;
        }
        String fallback = inferFallbackType(type);
        if (ComponentGeneratorRegistry.hasGenerator(fallback)) {
            FormacraftMod.LOGGER.debug("ComponentPlanCompiler: fallback component type {} -> {}", type, fallback);
            return fallback;
        }
        FormacraftMod.LOGGER.warn("ComponentPlanCompiler: skipping unsupported component type {}", type);
        return "";
    }

    static String inferFallbackType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (type.contains("PLAZA") || type.contains("PAVING") || type.contains("FLOOR")
                || type.contains("GROUND") || type.contains("PATH")) {
            return "PAVING";
        }
        if (type.contains("BENCH") || type.contains("LIGHT") || type.contains("GREEN")
                || type.contains("TREE") || type.contains("DECOR") || type.contains("ORNAMENT")
                || type.contains("STATUE") || type.contains("GARGOYLE")) {
            return "DECOR_DETAIL";
        }
        if (type.contains("ROOF")) {
            return "ROOF";
        }
        if (type.contains("CROWN") || type.contains("CUPOLA") || type.contains("DOME") || type.contains("SPIRE")) {
            return "CROWN";
        }
        if (type.contains("BALCONY")) {
            return "BALCONY";
        }
        if (type.contains("CHIMNEY")) {
            return "CHIMNEY";
        }
        if (type.contains("FOUNDATION") || type.contains("BASE")) {
            return "FOUNDATION";
        }
        if (type.contains("GATE")) {
            return "GATE";
        }
        if (type.contains("WINDOW")) {
            return "FACADE_WINDOWS";
        }
        if (type.contains("RAMPART") || type.contains("PALISADE") || type.contains("BARRIER")
                || type.contains("PARAPET") || type.contains("SCREEN")) {
            return "WALL";
        }
        if (type.contains("WALL") || type.contains("BUTTRESS")) {
            return "WALL";
        }
        if (type.contains("TERRACE")) {
            return "TERRACE";
        }
        if (type.contains("PALACE") || type.contains("TIER")) {
            return "MASS_MAIN";
        }
        if (type.contains("TOWER") || type.contains("SPIRE")) {
            return "TOWER";
        }
        return null;
    }

    static boolean isMassType(String type) {
        return "MASS_MAIN".equals(type)
                || "MASS_SECONDARY".equals(type)
                || "MASS_WING".equals(type)
                || "SIDE_WING".equals(type)
                || "MAIN_MASS".equals(type);
    }

    static boolean isRoofType(String type) {
        if (type == null) return false;
        return type.startsWith("ROOF");
    }

    static boolean isCrownType(String type) {
        if (type == null) {
            return false;
        }
        return "CROWN".equals(type) || "CUPOLA".equals(type) || "DOME".equals(type);
    }

    static boolean isStructureFloorType(String type) {
        return "FLOOR".equals(type) || "SLAB".equals(type) || "FLOOR_SLAB".equals(type)
                || "STRUCTURE_FLOOR".equals(type) || "INTERIOR_FLOOR".equals(type);
    }

    static String paramIdentity(Component c) {
        if (c == null || c.params() == null) return "?";
        Object id = c.params().get("component_id");
        return id == null ? "?" : String.valueOf(id);
    }

    static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /** Alias of {@link #getParamString(Map, String...)} kept for call-site clarity. */
    static String paramString(Map<String, Object> params, String... keys) {
        return getParamString(params, keys);
    }

    static Boolean getParamBoolean(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            switch (v) {
                case Boolean b -> {
                    return b;
                }
                case String s -> {
                    String t = s.trim().toLowerCase(Locale.ROOT);
                    if (t.equals("true") || t.equals("1") || t.equals("yes")) return true;
                    if (t.equals("false") || t.equals("0") || t.equals("no")) return false;
                }
                case Number n -> {
                    return n.doubleValue() != 0.0;
                }
                case null, default -> {
                }
            }
        }
        return null;
    }

    static boolean hasComponentRequest(List<String> features) {
        if (features == null || features.isEmpty()) {
            return false;
        }
        for (String feature : features) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.startsWith("component_request:") || lower.startsWith("group_request:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否携带地标/模块/整栋路由提示（Phase 7）。用于放行 MODULE 等未注册 type，
     * 交由 {@code UnifiedGeneratorRouter} 的整栋回退（{@code StructureGeneratorAdaptor}）处理。
     */
    static boolean hasStructureRoutingHint(Component component) {
        if (component == null) {
            return false;
        }
        List<String> features = component.features();
        if (features != null) {
            for (String feature : features) {
                if (feature == null) continue;
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.startsWith("landmark:") || lower.startsWith("module:")
                        || lower.startsWith("typology:")
                        || lower.startsWith("structure_generator:") || lower.startsWith("skeleton:")) {
                    return true;
                }
            }
        }
        Map<String, Object> params = component.params();
        if (params != null) {
            return params.containsKey("landmark") || params.containsKey("module_id")
                    || params.containsKey("typology_id") || params.containsKey("structural_typology")
                    || params.containsKey("template") || params.containsKey("blueprint")
                    || params.containsKey("assembly") || params.containsKey("skeleton")
                    || Boolean.TRUE.equals(params.get("useStructureGenerator"));
        }
        return false;
    }
}
