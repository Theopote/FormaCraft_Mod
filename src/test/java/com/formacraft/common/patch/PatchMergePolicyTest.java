package com.formacraft.common.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatchMergePolicyTest {
    @Test
    void massOverridesSameCoordinateShell() {
        var skeleton = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:oak_planks"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:glass"));
        var mass = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"));
        var merged = PatchMergePolicy.mergeSkeletonWithBuildingMass(skeleton, mass);
        assertEquals(2, merged.patches().size());
        assertEquals(1, merged.stats().overridden());
        assertEquals(1, merged.stats().skeletonOnly());
        assertEquals(0, merged.stats().massOnly());
        BlockPatch atOrigin = merged.patches().stream()
                .filter(p -> p.dx() == 0 && p.dy() == 1 && p.dz() == 0)
                .findFirst().orElseThrow();
        assertEquals("minecraft:stone", atOrigin.targetBlock());
    }

    @Test
    void nonOverlappingIsUnion() {
        var skeleton = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt"));
        var mass = List.of(new BlockPatch(BlockPatch.PLACE, 2, 0, 0, "minecraft:stone"));
        var merged = PatchMergePolicy.mergeSkeletonWithBuildingMass(skeleton, mass);
        assertEquals(2, merged.patches().size());
        assertEquals(0, merged.stats().overridden());
        assertEquals(1, merged.stats().skeletonOnly());
        assertEquals(1, merged.stats().massOnly());
    }
}
