package com.formacraft.common.component.variant;

import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.style.profile.BlockPalette;
import com.formacraft.common.style.profile.StyleProfile;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * SemanticMaterialApplier（语义材质替换器）v1：第二个"魔法点"。
 * <p>
 * Multi-tag voxels pick one tag via {@link ComponentPrototype.VariantRules.Material#tag_priority}
 * or the default STRUCTURE &gt; FRAME &gt; FILL &gt; sorted order, then apply through
 * {@link com.formacraft.common.component.semantic.ShapePreservingMaterialSwap}.
 */
public final class SemanticMaterialApplier {
    private SemanticMaterialApplier() {}

    private static final List<String> DEFAULT_PRIORITY = List.of("STRUCTURE", "FRAME", "FILL");

    public static void apply(
            VoxelGrid grid,
            ComponentPrototype.VariantRules.Material material,
            String materialSet,
            StyleProfile style
    ) {
        if (grid == null || material == null || style == null) return;
        if (material.semantic_map == null || material.semantic_map.isEmpty()) return;

        Random rand = new Random();
        BlockPalette palette = style.palette();
        if (palette == null) return;
        List<String> priority = material.tag_priority != null && !material.tag_priority.isEmpty()
                ? material.tag_priority
                : DEFAULT_PRIORITY;

        for (Voxel v : grid.all()) {
            String semanticTag = pickSemanticTag(v, priority);
            if (semanticTag == null) continue;

            String paletteKey = material.semantic_map.get(semanticTag);
            if (paletteKey == null) {
                // Allow case-insensitive map keys
                for (var e : material.semantic_map.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase(semanticTag)) {
                        paletteKey = e.getValue();
                        break;
                    }
                }
            }
            if (paletteKey == null) continue;

            String blockState = pickBlockFromPalette(palette, paletteKey, rand);
            if (blockState != null && !blockState.isBlank()) {
                v.setBlockState(com.formacraft.common.component.semantic.ShapePreservingMaterialSwap.apply(
                        v.blockState(), blockState));
            }
        }
    }

    /** Prefer author tag_priority, then default STRUCTURE/FRAME/FILL, else sorted. */
    static String pickSemanticTag(Voxel v, List<String> priority) {
        if (v == null || v.semanticTags().isEmpty()) return null;
        if (priority != null) {
            for (String p : priority) {
                if (p == null || p.isBlank()) continue;
                String want = p.trim().toUpperCase(Locale.ROOT);
                for (String tag : v.semanticTags()) {
                    if (tag != null && tag.equalsIgnoreCase(want)) return tag.toUpperCase(Locale.ROOT);
                }
            }
        }
        return v.semanticTags().stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.toUpperCase(Locale.ROOT))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static String pickBlockFromPalette(BlockPalette palette, String paletteKey, Random rand) {
        if (palette == null || paletteKey == null || paletteKey.isBlank()) return null;
        String key = paletteKey.trim().toLowerCase();

        return switch (key) {
            case "wall" -> palette.wall;
            case "roof" -> palette.roof;
            case "floor" -> palette.floor;
            case "window" -> palette.window;
            case "foundation" -> palette.foundation;
            case "trim" -> palette.trim;
            case "pillar", "column" -> palette.pillar;
            case "cap" -> palette.cap;
            default -> null;
        };
    }
}
