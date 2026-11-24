package com.formacraft.common.palette.component;

import net.minecraft.Bootstrap;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Returns only registered shapes from the same material family; null means no matching shape. */
public final class MaterialBlockFamily {
    private MaterialBlockFamily() {}
    public static String stairs(String block) { return shape(block, "stairs"); }
    public static String stairsOrStoneBrick(String block) {
        String stairs = stairs(block);
        return stairs != null ? stairs : "minecraft:stone_brick_stairs";
    }

    public static String slab(String block) { return shape(block, "slab"); }
    public static String wall(String block) { return shape(block, "wall"); }
    public static String fence(String block) { return shape(block, "fence"); }
    public static String pane(String block) { return shape(block, "pane"); }

    /** Wood-family trapdoor when registered; stone/terracotta/concrete return null. */
    public static String trapdoor(String block) {
        if (block == null || block.isBlank()) return null;
        String path = pathOf(block);
        if (path == null) return null;
        if (!(path.contains("plank") || path.contains("log") || path.contains("wood")
                || path.contains("oak") || path.contains("spruce") || path.contains("birch")
                || path.contains("jungle") || path.contains("acacia") || path.contains("dark_oak")
                || path.contains("mangrove") || path.contains("cherry") || path.contains("bamboo")
                || path.contains("crimson") || path.contains("warped"))) {
            return null;
        }
        return shape(block, "trapdoor");
    }

    /** Button when a matching family exists; otherwise null. */
    public static String button(String block) { return shape(block, "button"); }

    private static String pathOf(String block) {
        String base = block.split("\\[", 2)[0];
        if (!base.contains(":")) base = "minecraft:" + base;
        Identifier id = Identifier.tryParse(base);
        return id == null ? null : id.getPath();
    }

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
        if (!canUseBlockRegistry()) {
            return candidates[0];
        }
        try {
            if (!Registries.BLOCK.containsId(id)) return null;
            for (String candidate : candidates) {
                Identifier cid = Identifier.tryParse(candidate);
                if (cid != null && Registries.BLOCK.containsId(cid)) return candidate;
            }
            return null;
        } catch (ExceptionInInitializerError | NoClassDefFoundError | IllegalArgumentException | IllegalStateException e) {
            return candidates[0];
        }
    }

    private static String shape(String block, String shape) {
        if (block == null || block.isBlank()) return null;
        String base = block.split("\\[", 2)[0];
        if (!base.contains(":")) base = "minecraft:" + base;
        Identifier id = Identifier.tryParse(base);
        if (id == null) return null;
        if (!canUseBlockRegistry()) {
            // Unit tests / early parse before Minecraft Bootstrap — never touch Registries.
            return stringShapeCandidate(id, shape);
        }
        try {
            if (!Registries.BLOCK.containsId(id)) return null;
            String stem = stemOf(id.getPath());
            String candidate = id.getNamespace() + ":" + stem + "_" + shape;
            Identifier shapeId = Identifier.tryParse(candidate);
            return shapeId != null && Registries.BLOCK.containsId(shapeId) ? candidate : null;
        } catch (ExceptionInInitializerError | NoClassDefFoundError | IllegalArgumentException | IllegalStateException e) {
            return stringShapeCandidate(id, shape);
        }
    }

    /** Avoid Registries clinit before Bootstrap — a failed init poisons the whole JVM test run. */
    private static boolean canUseBlockRegistry() {
        try {
            Bootstrap.ensureBootstrapped(() -> "MaterialBlockFamily");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String stemOf(String name) {
        if (name.endsWith("_stairs")) return name.substring(0, name.length() - 7);
        if (name.endsWith("_slab")) return name.substring(0, name.length() - 5);
        if (name.endsWith("_wall")) return name.substring(0, name.length() - 5);
        if (name.endsWith("_fence")) return name.substring(0, name.length() - 6);
        if (name.endsWith("_pane")) return name.substring(0, name.length() - 5);
        if (name.endsWith("_trapdoor")) return name.substring(0, name.length() - 9);
        if (name.endsWith("_button")) return name.substring(0, name.length() - 7);
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
                || path.contains("glass") && !"pane".equals(shape) || path.equals("dirt") || path.equals("grass_block")) {
            return null;
        }
        if ("trapdoor".equals(shape) && !(path.contains("plank") || path.contains("oak") || path.contains("spruce")
                || path.contains("birch") || path.contains("jungle") || path.contains("acacia")
                || path.contains("dark_oak") || path.contains("mangrove") || path.contains("cherry")
                || path.contains("bamboo") || path.contains("crimson") || path.contains("warped")
                || path.contains("wood") || path.contains("log"))) {
            return null;
        }
        return id.getNamespace() + ":" + stem + "_" + shape;
    }

}
