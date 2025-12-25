package com.formacraft.common.style.profile;

import java.util.List;

/**
 * BlockPalette: material vocabulary for a style (v1).
 * Uses Minecraft block ids (e.g. minecraft:stone_bricks).
 */
public final class BlockPalette {
    public String wall;
    public String roof;
    public String floor;
    public String window;
    public String foundation;
    public String trim;
    public String pillar;
    /** Wall/fence cap (optional): e.g. minecraft:polished_blackstone_slab */
    public String cap;

    // Optional variants (future): not yet consumed by generators.
    public List<String> wallVariants;
    public List<String> roofVariants;
}


