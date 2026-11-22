package com.formacraft.common.palette.dynamic;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.*;

/** Locks actual emitted materials, without treating style defaults as explicit choices. */
public final class ExplicitMaterialPolicy {
    private ExplicitMaterialPolicy() {}

    private static final Set<String> EXPLICIT_BLOCK_KEYS = Set.of(
            "wall_block", "floor_block", "roof_block", "foundation_block");

    /** Validate authored roof IDs before general material fallback can replace them. */
    public static Optional<String> invalidAuthoredRoofId(StyleAttributes attrs, List<Component> components) {
        if (attrs != null && invalidRoofId(attrs.roofMaterial())) {
            return Optional.of("style_attributes.roof_material");
        }
        var paletteRoof = DynamicPaletteResolver.roofPaletteEntry(attrs);
        if (paletteRoof != null && invalidRoofId(paletteRoof.getValue())) {
            return Optional.of(paletteRoof.getKey());
        }
        if (components == null) return Optional.empty();
        for (int index = 0; index < components.size(); index++) {
            Component component = components.get(index);
            if (component == null || component.params() == null) continue;
            boolean roof = "ROOF".equalsIgnoreCase(component.componentType())
                    || "ROOF_STRUCTURE".equalsIgnoreCase(component.componentType());
            for (String key : List.of("roof_block", "roof_material", "material")) {
                if (("material".equals(key) && !roof) || !invalidRoofId(component.params().get(key))) continue;
                return Optional.of("components[" + index + "].params." + key);
            }
        }
        return Optional.empty();
    }

    private static boolean invalidRoofId(Object value) {
        if (!(value instanceof String text)) return false;
        String candidate = text.trim();
        String base = candidate.split("\\[", 2)[0];
        boolean explicitId = base.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || candidate.contains("[");
        return explicitId && BlockPatchTargetResolver.parse(candidate) == null;
    }

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
                String key = String.valueOf(entry.getKey());
                if (EXPLICIT_BLOCK_KEYS.contains(key) && entry.getValue() != null) {
                    String text = String.valueOf(entry.getValue());
                    if ("foundation_block".equals(key)) {
                        if (resolveFoundationMaterial(text) == null)
                            return Optional.of(childPath + "=" + text);
                    } else if (canonical(text) == null) {
                        return Optional.of(childPath + "=" + text);
                    }
                }
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
                String key = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof String text) {
                    if ("foundation_block".equals(key)) {
                        // Protect-set keys must match emit-pass canonical(patch) identity.
                        String resolved = resolveFoundationMaterial(text);
                        if (resolved != null) add(targets, resolved);
                    } else if (Set.of("wall_block", "floor_block", "roof_block", "material", "block",
                            "trim_block", "glass_block", "glazing_block", "glass_material").contains(key)) {
                        add(targets, text);
                    }
                } else if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) {
                    collect(targets, entry.getValue());
                }
            }
        } else if (value instanceof List<?> list) {
            for (var entry : list) collect(targets, entry);
        }
    }

    /**
     * Resolve an authored foundation material to a placeable BlockState string.
     * Rejects unknown ids, air/fluids, replaceable plants, empty-collision blocks
     * (torch, signs, …), and gravity/{@link FallingBlock} materials (sand, gravel, …)
     * that would otherwise be silently remapped by {@link GravityBlockPolicy}.
     * Stilts/special members still use this gate — placeable posts need collision.
     */
    public static String resolveFoundationMaterial(String target) {
        if (target == null || target.isBlank()) return null;
        String text = target.trim();
        String mapped = DynamicPaletteResolver.mapMaterialToBlock(text);
        String candidate = mapped != null ? mapped : (text.contains(":") ? text : "minecraft:" + text);
        BlockState state = BlockPatchTargetResolver.parse(candidate);
        if (state == null) return null;
        if (!isStructuralFoundationState(state)) return null;
        // Emit-facing identity; protect-set collect() re-canonicalizes for postprocess keys.
        return BlockStateStringUtil.fromState(state);
    }

    /**
     * HARD_CONSTRAINT: structural foundation / stilt posts need stable, collision-bearing solids.
     * Gravity blocks are rejected here (explicit fail) rather than silently stabilized later.
     */
    public static boolean isStructuralFoundationState(BlockState state) {
        if (state == null) return false;
        if (state.isAir() || state.getBlock() instanceof FluidBlock || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getBlock() instanceof FallingBlock) return false;
        if (state.isReplaceable()) return false;
        return !state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty();
    }

    public static String canonical(String target) {
        var state = BlockPatchTargetResolver.parse(target);
        if (state != null) {
            // Keep historical BlockState.toString() identity for emit-pass protect sets.
            return state.toString();
        }
        // Accept planner aliases (stone_brick_slabs → stone_brick_slab) before hard-fail.
        String mapped = DynamicPaletteResolver.mapMaterialToBlock(target);
        if (mapped == null || mapped.equals(target)) {
            return null;
        }
        state = BlockPatchTargetResolver.parse(mapped);
        return state == null ? null : state.toString();
    }

    private static void add(Set<String> targets, String target) {
        String canonical = canonical(target);
        if (canonical != null) targets.add(canonical);
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
