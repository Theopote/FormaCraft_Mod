package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatchOccupancyPlazaTest {
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void plazaYieldsToBuildingWithoutGrantingOverwriteAuthority() {
        var index = new PatchOccupancyIndex();
        var mass = component("MASS_MAIN");
        var plaza = component("PLAZA");
        index.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks")));
        var patches = new ArrayList<>(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:cobblestone")));
        assertTrue(index.validate(plaza, patches).isPresent());
        assertEquals(1, index.trimIncompatibleAttachment(plaza, patches, Set.of()));
        assertTrue(index.accept(plaza, patches).isEmpty());
        assertEquals("minecraft:stone_bricks", index.placeAt(BlockPos.ORIGIN).targetBlock());
        assertEquals("minecraft:cobblestone", index.placeAt(new BlockPos(1, 0, 0)).targetBlock());
        assertFalse(PatchOccupancyIndex.canOverwritePlace(plaza));
    }
    private static Component component(String type) {
        return new Component(type, null, new Vec3i(0, 0, 0), new Dimensions(2, 1, 1), List.of(), Map.of());
    }
}
