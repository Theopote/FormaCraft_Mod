package com.formacraft.common.palette.dynamic;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.StairsBlock;
import java.util.*;

/** Resolve planner vocabulary before validation and material protection see the plan. */
public final class MaterialFallbackPolicy {
    private MaterialFallbackPolicy() {}

    public static LlmPlan normalize(LlmPlan p) {
        var a = p.styleAttributes();
        var attrs = a == null ? null : new StyleAttributes(a.wallColor(),
                material(a.wallMaterial(), "wall_material"), a.roofColor(),
                material(a.roofMaterial(), "roof_material"), material(a.accentMaterial(), "accent_material"),
                material(a.floorMaterial(), "floor_material"), a.decorativeElements(), a.customAttributes());
        var components = p.components() == null ? null : p.components().stream().map(c -> c == null ? null :
                new Component(c.componentType(), c.slotId(), c.relativePosition(), c.dimensions(), c.features(),
                        normalizeParams(c.params()))).toList();
        return new LlmPlan(p.mode(), p.styleProfile(), p.anchor(), p.globalConstraints(), p.layout(), components,
                p.genome(), attrs, p.proportionHints(), p.alignmentAndSymmetry(), p.targetSlotId(), p.allowedArea(),
                p.patch(), p.planProgram(), p.planSkeleton(), p.planStatus(), p.error(), p.capabilityGap(),
                p.playerFidelityNoticeZh(), p.distinguishingFeatures(), p.enrichmentGuard(), p.planMeta(), p.designSources());
    }

    private static Map<String, Object> normalizeParams(Map<String, Object> params) {
        if (params == null) return null;
        var copy = new LinkedHashMap<String, Object>();
        params.forEach((k, v) -> copy.put(k, normalizeValue(k, v)));
        return copy;
    }

    private static Object normalizeValue(String key, Object value) {
        // FBD has its own typed roles and expressions. Treat its AST as opaque:
        // e.g. floor="floor" is a role, not an unresolved Minecraft block ID.
        if ("fbd_program".equals(key)) return value;
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), normalizeValue(String.valueOf(k), v)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> normalizeValue(key, v)).toList();
        if (Set.of("stairs", "steps", "stair_block").contains(key) && value instanceof String requested) {
            var state = BlockPatchTargetResolver.resolve(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, requested));
            if (state != null && state.getBlock() instanceof StairsBlock) return value;
            String replacement = requested.contains("wood") || requested.contains("oak") || requested.contains("timber")
                    ? "minecraft:oak_stairs" : "minecraft:stone_brick_stairs";
            FormacraftMod.LOGGER.warn("Material fallback: {}={} -> {}", key, requested, replacement);
            return replacement;
        }
        if ((key.endsWith("_block") || key.endsWith("_material")
                || Set.of("material", "block", "floor", "supportMaterial").contains(key)) && value instanceof String) {
            String text = String.valueOf(value);
            if (ExplicitMaterialPolicy.canonical(text) != null) {
                return stabilizeMaterial(text, key);
            }
            return material(text.isBlank() ? "unspecified" : text, key);
        }
        return value;
    }

    public static String material(String requested, String role) {
        if (requested == null || requested.isBlank()) return requested;
        String exact = DynamicPaletteResolver.mapMaterialToBlock(requested);
        if (exact != null) return stabilizeMaterial(exact, role);
        String name = requested.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String replacement;
        if (role.contains("door")) replacement = "minecraft:oak_door";
        else if (role.contains("rail") || role.contains("fence")) replacement = "minecraft:oak_fence";
        else if (name.contains("gravel") || name.contains("沙砾") || name.contains("碎石"))
            replacement = "minecraft:cobblestone";
        else if (name.contains("tile") || name.contains("slate") || name.contains("瓦"))
            replacement = name.contains("red") ? "minecraft:bricks" : "minecraft:deepslate_tiles";
        else if (name.contains("glass") || role.contains("glass") || role.contains("glazing")) replacement = "minecraft:glass";
        else if (name.contains("timber") || name.contains("wood") || name.contains("木"))
            replacement = name.contains("dark") ? "minecraft:dark_oak_planks" : "minecraft:oak_planks";
        else if (name.contains("plaster") || name.contains("marble") || name.contains("灰泥")) replacement = "minecraft:white_concrete";
        else if (name.contains("brick") || name.contains("stone") || name.contains("石") || name.contains("砖")) replacement = "minecraft:stone_bricks";
        else if (name.contains("metal") || name.contains("iron")) replacement = "minecraft:iron_block";
        else replacement = switch (role) {
            case "roof_material", "roof_block" -> "minecraft:deepslate_tiles";
            case "accent_material", "trim_block" -> "minecraft:oak_planks";
            default -> "minecraft:stone_bricks";
        };
        FormacraftMod.LOGGER.warn("Material fallback: {}={} -> {}", role, requested, replacement);
        return stabilizeMaterial(replacement, role);
    }

    private static String stabilizeMaterial(String blockId, String role) {
        String stable = GravityBlockPolicy.stabilize(blockId);
        if (!stable.equals(blockId)) {
            FormacraftMod.LOGGER.info("Gravity block remap: {}={} -> {}", role, blockId, stable);
        }
        return stable;
    }
}
