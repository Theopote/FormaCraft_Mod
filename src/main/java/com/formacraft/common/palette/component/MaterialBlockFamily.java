package com.formacraft.common.palette.component;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Returns only registered shapes from the same material family; null means no matching shape. */
public final class MaterialBlockFamily {
    private MaterialBlockFamily() {}
    public static String stairs(String block) { return shape(block, "stairs"); }
    public static String slab(String block) { return shape(block, "slab"); }

    /**
     * Full cube in the same family as stairs/slab (for slope fill under stair surfaces).
     * e.g. oak_stairs → oak_planks, stone_brick_stairs → stone_bricks.
     */
    public static String fullBlock(String block) {
        if (block == null || block.isBlank()) return null;
        String base = block.split("\\[", 2)[0];
        if (!base.contains(":")) base = "minecraft:" + base;
        Identifier id = Identifier.tryParse(base);
        if (id == null) return null;
        String name = id.getPath();
        if (!name.endsWith("_stairs") && !name.endsWith("_slab")) {
            return base; // already a full-ish block
        }
        String stem = stemOf(name);
        String ns = id.getNamespace();
        String[] candidates = {
                ns + ":" + stem + "_planks",
                ns + ":" + stem + "s",          // stone_brick → stone_bricks
                ns + ":" + stem + "_bricks",
                ns + ":" + stem + "_tiles",
                ns + ":" + stem + "_block",
                ns + ":" + stem
        };
        try {
            if (!Registries.BLOCK.containsId(id)) return null;
            for (String candidate : candidates) {
                Identifier cid = Identifier.tryParse(candidate);
                if (cid != null && Registries.BLOCK.containsId(cid)) return candidate;
            }
            return null;
        } catch (ExceptionInInitializerError | IllegalArgumentException | IllegalStateException e) {
            return candidates[0];
        }
    }

    private static String shape(String block, String shape) {
        if (block == null || block.isBlank()) return null;
        String base = block.split("\\[", 2)[0];
        if (!base.contains(":")) base = "minecraft:" + base;
        Identifier id = Identifier.tryParse(base);
        if (id == null) return null;
        try {
            if (!Registries.BLOCK.containsId(id)) return null;
            String stem = stemOf(id.getPath());
            String candidate = id.getNamespace() + ":" + stem + "_" + shape;
            Identifier shapeId = Identifier.tryParse(candidate);
            return shapeId != null && Registries.BLOCK.containsId(shapeId) ? candidate : null;
        } catch (ExceptionInInitializerError | IllegalArgumentException | IllegalStateException e) {
            // Unit tests / early parse before Minecraft Bootstrap.
            return stringShapeCandidate(id, shape);
        }
    }

    private static String stemOf(String name) {
        if (name.endsWith("_stairs")) return name.substring(0, name.length() - 7);
        if (name.endsWith("_slab")) return name.substring(0, name.length() - 5);
        if (name.endsWith("_planks")) return name.substring(0, name.length() - 7);
        if (name.equals("quartz_block")) return "quartz";
        if (name.equals("bricks")) return "brick";
        if (name.endsWith("_bricks")) return name.substring(0, name.length() - 1);
        if (name.endsWith("_tiles")) return name.substring(0, name.length() - 1);
        return name;
    }

    /** Known stair/slab families when registries are unavailable (unit tests). */
    private static String stringShapeCandidate(Identifier id, String shape) {
        String stem = stemOf(id.getPath());
        // Terracotta / concrete / wool have no stair variants — leave roof as full block.
        String path = id.getPath();
        if (path.contains("terracotta") || path.contains("concrete") || path.contains("wool")
                || path.contains("glass") || path.equals("dirt") || path.equals("grass_block")) {
            return null;
        }
        return id.getNamespace() + ":" + stem + "_" + shape;
    }

}
