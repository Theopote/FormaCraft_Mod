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

    @Test void hostBoundPrimitiveBalconyMayOverwriteMass() {
        var index = new PatchOccupancyIndex();
        var mass = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(12, 12, 20), List.of(),
                Map.of("component_id", "cyberpunk_highrise_main"));
        var balconyFloor = new Component("PRIMITIVE", "main", new Vec3i(0, 4, 11), new Dimensions(4, 1, 2), List.of(),
                Map.of("component_id", "cyberpunk_highrise_balcony_floor",
                        "host_id", "cyberpunk_highrise_main",
                        "kind", "box"));
        index.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 4, 11, "minecraft:cyan_concrete")));
        assertTrue(index.accept(balconyFloor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 4, 11, "minecraft:polished_andesite"))).isEmpty());
        assertEquals("PRIMITIVE", index.placeAt(new BlockPos(0, 4, 11)).writerType());
        assertEquals("minecraft:polished_andesite", index.placeAt(new BlockPos(0, 4, 11)).targetBlock());
    }

    @Test void gateMayOverwritePathAtThreshold() {
        var index = new PatchOccupancyIndex();
        var path = new Component("PATH", null, new Vec3i(0, 0, 0), new Dimensions(4, 1, 1), List.of(),
                Map.of("component_id", "manor_path"));
        var gate = new Component("GATE", null, new Vec3i(0, 0, 0), new Dimensions(3, 5, 1), List.of(),
                Map.of("component_id", "manor_gate", "host_id", "manor_main"));
        index.commit(path, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone")));
        assertTrue(index.accept(gate, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks"))).isEmpty());
        assertEquals("GATE", index.placeAt(BlockPos.ORIGIN).writerType());
        assertEquals("minecraft:stone_bricks", index.placeAt(BlockPos.ORIGIN).targetBlock());
    }

    private static Component component(String type) {
        return new Component(type, null, new Vec3i(0, 0, 0), new Dimensions(2, 1, 1), List.of(), Map.of());
    }
}
