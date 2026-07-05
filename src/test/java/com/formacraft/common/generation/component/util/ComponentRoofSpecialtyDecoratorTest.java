package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentRoofSpecialtyDecoratorTest {

    @Test
    void computeMansardRise_isLowAtEdgeAndHigherInCenter() {
        int edge = ComponentRoofSpecialtyDecorator.computeMansardRise(0, 0, 13, 11, 6);
        int center = ComponentRoofSpecialtyDecorator.computeMansardRise(6, 5, 13, 11, 6);
        assertTrue(edge < center);
        assertTrue(center >= 4);
    }

    @Test
    void emitDormers_addsGlassAndRoofCap() {
        List<BlockPatch> roof = new ArrayList<>();
        int width = 13;
        int depth = 11;
        int height = 6;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int rise = ComponentRoofSpecialtyDecorator.computeMansardRise(x, z, width, depth, height);
                roof.add(new BlockPatch(BlockPatch.PLACE, x, rise, z, "minecraft:bricks"));
            }
        }
        List<BlockPatch> out = new ArrayList<>(roof);
        ComponentRoofSpecialtyDecorator.emitDormers(
                out,
                roof,
                0,
                0,
                0,
                width,
                depth,
                height,
                "minecraft:stone_bricks",
                "minecraft:glass_pane",
                "minecraft:bricks"
        );
        assertTrue(out.size() > roof.size());
        assertTrue(out.stream().anyMatch(p -> p.targetBlock() != null && p.targetBlock().contains("glass")));
        assertTrue(out.stream().anyMatch(p -> p.targetBlock() != null && p.targetBlock().contains("stairs")));
    }
}
