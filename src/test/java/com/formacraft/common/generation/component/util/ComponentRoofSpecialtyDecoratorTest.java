package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentRoofSpecialtyDecoratorTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test
    void computeMansardRise_isLowAtEdgeAndHigherInCenter() {
        int edge = ComponentRoofSpecialtyDecorator.computeMansardRise(0, 0, 13, 11, 6);
        int center = ComponentRoofSpecialtyDecorator.computeMansardRise(6, 5, 13, 11, 6);
        assertTrue(edge < center);
        assertTrue(center >= 4);
    }

    @Test
    void authoredMansardFoldRejectsDegenerateOrReversedSlopes() {
        assertThrows(IllegalArgumentException.class,
                () -> ComponentRoofSpecialtyDecorator.resolveMansardProfile(13, 17, 11,
                        Map.of("mansard_break_run", 6, "mansard_break_rise", 7)));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentRoofSpecialtyDecorator.resolveMansardProfile(13, 17, 11,
                        Map.of("mansard_break_run", 3, "mansard_break_rise", 4)));
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

    @Test
    void requestedDormersCannotSpanAGapInTheFoldStrip() {
        // Depth > width — dormers sit on the major (long) face, centered along Z at x=setback.
        var profile = ComponentRoofSpecialtyDecorator.resolveMansardProfile(13, 17, 11,
                Map.of("mansard_break_run", 3, "mansard_break_rise", 7));
        int setback = profile.setback();
        List<BlockPatch> roof = new ArrayList<>();
        for (int z = 3; z <= 9; z++) {
            roof.add(new BlockPatch(BlockPatch.PLACE, setback, 7, z, "minecraft:bricks"));
            if (z != 6) roof.add(new BlockPatch(BlockPatch.PLACE, setback - 1, 5, z, "minecraft:bricks"));
        }
        List<BlockPatch> out = new ArrayList<>();
        var error = assertThrows(IllegalArgumentException.class,
                () -> ComponentRoofSpecialtyDecorator.emitDormers(out, roof, 0, 0, 0,
                        13, 17, profile, 2, "minecraft:stone_bricks", "minecraft:glass_pane", "minecraft:bricks"));
        assertTrue(error.getMessage().contains("capacity of 1"));
        assertTrue(out.isEmpty());
        ComponentRoofSpecialtyDecorator.emitDormers(out, roof, 0, 0, 0,
                13, 17, profile, 1, "minecraft:stone_bricks", "minecraft:glass_pane", "minecraft:bricks");
        assertEquals(List.of(4), out.stream().filter(p -> p.dy() == 8 && "minecraft:glass_pane".equals(p.targetBlock()))
                .map(BlockPatch::dz).toList());
    }

    @Test
    void dormerAxisParamOverridesLongerFacePreference() {
        assertTrue(ComponentRoofSpecialtyDecorator.resolveDormerAlongWidth(
                13, 17, null, List.of(), 0, 0, 3, Map.of("dormer_axis", "width")));
        assertTrue(!ComponentRoofSpecialtyDecorator.resolveDormerAlongWidth(
                17, 13, null, List.of(), 0, 0, 3, Map.of("dormer_face", "east")));
    }
}
