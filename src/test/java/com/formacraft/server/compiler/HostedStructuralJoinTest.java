package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostedStructuralJoinTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private Component component(String type, Map<String, Object> params) {
        return new Component(type, "main", new Vec3i(0,0,0), new Dimensions(10,10,8), List.of(), params);
    }
    @Test void ownFloorAndRoofCanJoinHostButUnrelatedMassCannot() {
        for (String type : List.of("ROOF", "MASS_SECONDARY")) {
            var index = new PatchOccupancyIndex();
            var pos = List.of(new BlockPatch(BlockPatch.PLACE, 1,4,1,"minecraft:stone_bricks"));
            index.commit(component("MASS_MAIN", Map.of("component_id","house")), pos);
            var replacement = List.of(new BlockPatch(BlockPatch.PLACE,1,4,1,"minecraft:oak_planks"));
            assertTrue(index.validate(component(type, Map.of("host_id","other", "extrude_mode","plate")), replacement).isPresent());
            assertTrue(index.validate(component("MASS_SECONDARY", Map.of("host_id","house")), replacement).isPresent());
            assertTrue(index.accept(component(type, Map.of("host_id","house", "extrude_mode","plate")), replacement).isEmpty());
        }
    }
    @Test void defaultCompletionRespectsExplicitStrictMode() {
        assertTrue(LlmPlanTestFixtures.builder().build().allowCompilerSatellites());
        assertFalse(LlmPlanTestFixtures.builder().planMeta(Map.of("allow_compiler_satellites",false)).build().allowCompilerSatellites());
    }
    @Test void attachedTowerMayJoinOnlyItsOwnHostInTheSameFrame() {
        var index = new PatchOccupancyIndex();
        index.commit(component("MASS_MAIN",Map.of("component_id","hall")),
                List.of(new BlockPatch(BlockPatch.PLACE,1,0,1,"minecraft:stone_bricks")));
        var patch = List.of(new BlockPatch(BlockPatch.PLACE,1,0,1,"minecraft:oak_planks"));
        assertTrue(index.validate(component("TOWER",Map.of("component_id","tower","host_id","hall")),patch).isEmpty());
        assertTrue(index.validate(component("TOWER",Map.of("component_id","tower","host_id","other")),patch).isPresent());
        var foreignFrame = new Component("TOWER","other_slot",new Vec3i(0,0,0),new Dimensions(7,7,24),List.of(),
                Map.of("component_id","tower","host_id","hall"));
        assertTrue(index.validate(foreignFrame,patch).isPresent());
    }
    @Test void tierCanJoinHostTopButCannotOverwriteItsInterior() {
        var index = new PatchOccupancyIndex();
        index.commit(component("MASS_MAIN", Map.of("component_id", "house")),
                List.of(new BlockPatch(BlockPatch.PLACE, 1,7,1,"minecraft:stone_bricks")));
        var params = Map.<String,Object>of("component_id", "tier", "host_id", "house", "tier_index", 1, "anchor_mode", "min_corner");
        var tier = new Component("MASS_SECONDARY", "main", new Vec3i(0,7,0), new Dimensions(10,10,5), List.of(), params);
        var patch = List.of(new BlockPatch(BlockPatch.PLACE,1,7,1,"minecraft:oak_planks"));
        assertTrue(index.validate(tier, patch).isEmpty());
        var intersecting = new Component("MASS_SECONDARY", "main", new Vec3i(0,2,0), tier.dimensions(), List.of(), params);
        assertTrue(index.validate(intersecting, patch).isPresent());
    }
    @Test void optionalAttachmentClipsConflictWithoutChangingPrimaryOrLedger() {
        var index = new PatchOccupancyIndex();
        var original = new BlockPatch(BlockPatch.PLACE, 1, 4, 1, "minecraft:stone_bricks");
        index.commit(component("MASS_MAIN", Map.of("component_id", "house")), List.of(original));
        var conflict = new BlockPatch(BlockPatch.PLACE, 1, 4, 1, "minecraft:oak_planks");
        var free = new BlockPatch(BlockPatch.PLACE, 2, 4, 1, "minecraft:oak_planks");
        var decor = component("DECOR_DETAIL", Map.of("host_id", "other"));
        var patches = new ArrayList<>(List.of(conflict, free));
        assertEquals(1, index.trimIncompatibleAttachment(decor, patches, Set.of()));
        assertEquals(List.of(free), patches);
        assertEquals("minecraft:stone_bricks", index.placeAt(new net.minecraft.util.math.BlockPos(1,4,1)).targetBlock());
        assertNull(index.placeAt(new net.minecraft.util.math.BlockPos(2,4,1)));
        assertTrue(index.accept(decor, patches).isEmpty());
        var primaryPatches = new ArrayList<>(List.of(conflict));
        var primary = component("MASS_MAIN", Map.of("component_id", "other"));
        assertEquals(0, index.trimIncompatibleAttachment(primary, primaryPatches, Set.of()));
        assertTrue(index.validate(primary, primaryPatches).isPresent());
    }
}
