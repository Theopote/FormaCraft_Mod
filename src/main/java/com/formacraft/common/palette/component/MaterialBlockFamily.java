package com.formacraft.common.palette.component;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Returns only registered shapes from the same material family; null means no matching shape. */
public final class MaterialBlockFamily {
    private MaterialBlockFamily() {}
    public static String stairs(String block) { return shape(block, "stairs"); }
    public static String slab(String block) { return shape(block, "slab"); }
    private static String shape(String block, String shape) {
        if (block == null || block.isBlank()) return null;
        String base = block.split("\\[", 2)[0];
        if (!base.contains(":")) base = "minecraft:" + base;
        Identifier id = Identifier.tryParse(base);
        if (id == null || !Registries.BLOCK.containsId(id)) return null;
        String name = id.getPath();
        String stem;
        if (name.endsWith("_stairs")) stem = name.substring(0, name.length() - 7);
        else if (name.endsWith("_slab")) stem = name.substring(0, name.length() - 5);
        else if (name.endsWith("_planks")) stem = name.substring(0, name.length() - 7);
        else if (name.equals("quartz_block")) stem = "quartz";
        else if (name.equals("bricks")) stem = "brick";
        else if (name.endsWith("_bricks")) stem = name.substring(0, name.length() - 1);
        else if (name.endsWith("_tiles")) stem = name.substring(0, name.length() - 1);
        else stem = name;
        String candidate = id.getNamespace() + ":" + stem + "_" + shape;
        Identifier shapeId = Identifier.tryParse(candidate);
        return shapeId != null && Registries.BLOCK.containsId(shapeId) ? candidate : null;
    }
}
