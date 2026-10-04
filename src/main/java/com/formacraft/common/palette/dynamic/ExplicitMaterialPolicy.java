package com.formacraft.common.palette.dynamic;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import java.util.*;

/** Locks actual emitted materials, without treating style defaults as explicit choices. */
public final class ExplicitMaterialPolicy {
    private ExplicitMaterialPolicy() {}

    public static Optional<String> invalidAttribute(StyleAttributes attrs) {
        return invalidAttribute(attrs, List.of());
    }

    public static Optional<String> invalidAttribute(StyleAttributes attrs, List<Component> components) {
        if (attrs == null) return Optional.empty();
        var fields = new LinkedHashMap<String, String>();
        fields.put("wall_material", attrs.wallMaterial());
        fields.put("roof_material", attrs.roofMaterial());
        fields.put("floor_material", attrs.floorMaterial());
        fields.put("accent_material", attrs.accentMaterial());
        for (var entry : fields.entrySet()) {
            String role = entry.getKey().replace("_material", "_block");
            var masses = components == null ? List.<Component>of() : components.stream()
                    .filter(c -> c != null && "MASS_MAIN".equals(c.componentType())).toList();
            if (entry.getValue() != null && entry.getValue().contains("building_") && entry.getValue().contains("/")
                    && masses.size() > 1 && masses.stream().allMatch(c -> c.params() != null
                        && c.params().get(role) instanceof String material && canonical(material) != null)) continue;
            if (present(entry.getValue()) && DynamicPaletteResolver.mapMaterialToBlock(entry.getValue()) == null)
                return Optional.of("style_attributes." + entry.getKey() + "=" + entry.getValue());
        }
        return Optional.empty();
    }

    public static Set<String> targets(Component component, StyleAttributes attrs) {
        var targets = new HashSet<String>();
        if (attrs != null) {
            if (present(attrs.wallMaterial())) add(targets, DynamicPaletteResolver.resolve(com.formacraft.common.semantic.SemanticPart.WALL, attrs));
            if (present(attrs.roofMaterial())) add(targets, DynamicPaletteResolver.resolve(com.formacraft.common.semantic.SemanticPart.ROOF, attrs));
            if (present(attrs.floorMaterial())) add(targets, DynamicPaletteResolver.resolve(com.formacraft.common.semantic.SemanticPart.FLOOR, attrs));
            if (present(attrs.accentMaterial())) add(targets, DynamicPaletteResolver.resolve(com.formacraft.common.semantic.SemanticPart.DECOR, attrs));
        }
        if (component != null) collect(targets, component.params());
        return targets;
    }

    public static Optional<String> invalidComponent(Component component) {
        return invalidBlocks(component.params(), "components[]." + component.componentType() + ".params");
    }

    private static Optional<String> invalidBlocks(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String childPath = path + "." + entry.getKey();
                if (Set.of("wall_block", "floor_block", "roof_block").contains(String.valueOf(entry.getKey()))
                        && entry.getValue() != null && canonical(String.valueOf(entry.getValue())) == null)
                    return Optional.of(childPath + "=" + entry.getValue());
                if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) {
                    var invalid = invalidBlocks(entry.getValue(), childPath);
                    if (invalid.isPresent()) return invalid;
                }
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                var invalid = invalidBlocks(list.get(i), path + "[" + i + "]");
                if (invalid.isPresent()) return invalid;
            }
        }
        return Optional.empty();
    }

    private static void collect(Set<String> targets, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (Set.of("wall_block", "floor_block", "roof_block", "material", "block", "trim_block",
                        "glass_block", "glazing_block", "glass_material").contains(String.valueOf(entry.getKey()))
                        && entry.getValue() instanceof String text) add(targets, text);
                else if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) collect(targets, entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (var entry : list) collect(targets, entry);
        }
    }

    public static String canonical(String target) {
        var state = BlockPatchTargetResolver.parse(target);
        return state == null ? null : state.toString();
    }

    private static void add(Set<String> targets, String target) {
        String canonical = canonical(target);
        if (canonical != null) targets.add(canonical);
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
