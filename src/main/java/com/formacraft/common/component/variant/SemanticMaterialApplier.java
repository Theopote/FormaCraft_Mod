package com.formacraft.common.component.variant;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.style.profile.BlockPalette;
import com.formacraft.common.style.profile.StyleProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * SemanticMaterialApplier（语义材质替换器）v1：第二个"魔法点"。
 * <p>
 * Multi-tag voxels: filter to tags present in {@code semantic_map}, then pick by
 * STRUCTURE/STRUCTURAL → FRAME → SURFACE → FILL → ORNAMENT → author {@code tag_priority} → sorted.
 * Apply through {@link com.formacraft.common.component.semantic.ShapePreservingMaterialSwap}.
 */
public final class SemanticMaterialApplier {
    private SemanticMaterialApplier() {}

    /** Built-in rank before author tag_priority; STRUCTURAL aliases STRUCTURE. */
    private static final List<String> BUILTIN_PRIORITY = List.of(
            "STRUCTURE", "STRUCTURAL", "FRAME", "SURFACE", "FILL", "ORNAMENT");

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

        for (Voxel v : grid.all()) {
            String semanticTag = pickSemanticTag(v, material.semantic_map, material.tag_priority);
            if (semanticTag == null) continue;

            String paletteKey = lookupMap(material.semantic_map, semanticTag);
            if (paletteKey == null) continue;

            String blockState = pickBlockFromPalette(palette, paletteKey, rand);
            if (blockState != null && !blockState.isBlank()) {
                v.setBlockState(com.formacraft.common.component.semantic.ShapePreservingMaterialSwap.apply(
                        v.blockState(), blockState));
            }
        }
    }

    /**
     * Filter tags to those present in {@code semantic_map}, then apply priority.
     * Package-visible for tests.
     */
    static String pickSemanticTag(Voxel v, Map<String, String> semanticMap, List<String> authorPriority) {
        if (v == null || v.semanticTags().isEmpty() || semanticMap == null || semanticMap.isEmpty()) {
            return null;
        }
        List<String> mapped = new ArrayList<>();
        for (String tag : v.semanticTags()) {
            if (tag == null || tag.isBlank()) continue;
            String upper = tag.trim().toUpperCase(Locale.ROOT);
            if (lookupMap(semanticMap, upper) != null) mapped.add(upper);
        }
        if (mapped.isEmpty()) return null;

        List<String> order = new ArrayList<>(BUILTIN_PRIORITY.size() + 8);
        order.addAll(BUILTIN_PRIORITY);
        if (authorPriority != null) {
            for (String p : authorPriority) {
                if (p == null || p.isBlank()) continue;
                String want = p.trim().toUpperCase(Locale.ROOT);
                if (!order.contains(want)) order.add(want);
            }
        }

        String chosen = null;
        int chosenRank = Integer.MAX_VALUE;
        Set<String> ties = new LinkedHashSet<>();
        for (String tag : mapped) {
            int rank = rankOf(tag, order);
            if (rank < chosenRank) {
                chosenRank = rank;
                chosen = tag;
                ties.clear();
                ties.add(tag);
            } else if (rank == chosenRank) {
                ties.add(tag);
            }
        }
        if (chosen == null) {
            return mapped.stream().sorted().findFirst().orElse(null);
        }
        if (ties.size() > 1) {
            chosen = ties.stream().sorted().findFirst().orElse(chosen);
            FormacraftMod.LOGGER.debug(
                    "[SemanticMaterialApplier] tied semantic tags {} at rank {}; chose {}",
                    ties, chosenRank, chosen);
        }
        return chosen;
    }

    /** Legacy test helper: priority list only (no map filter). */
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

    private static int rankOf(String tag, List<String> order) {
        // STRUCTURAL shares STRUCTURE's builtin slot so alias ties do not reorder.
        String canon = "STRUCTURAL".equals(tag) ? "STRUCTURE" : tag;
        for (int i = 0; i < order.size(); i++) {
            String o = order.get(i);
            String oCanon = "STRUCTURAL".equals(o) ? "STRUCTURE" : o;
            if (oCanon.equals(canon)) return i;
        }
        return Integer.MAX_VALUE / 2;
    }

    private static String lookupMap(Map<String, String> map, String tag) {
        if (map.containsKey(tag)) return map.get(tag);
        for (var e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(tag)) return e.getValue();
            // STRUCTURE / STRUCTURAL alias both ways
            if ("STRUCTURE".equalsIgnoreCase(tag) && "STRUCTURAL".equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
            if ("STRUCTURAL".equalsIgnoreCase(tag) && "STRUCTURE".equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
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
            case "door" -> palette.door;
            case "roof_trim", "rooftrim" -> palette.roofTrim;
            case "stairs" -> palette.stairs;
            case "roof_slab", "roofslab" -> palette.roofSlab;
            default -> null;
        };
    }
}
