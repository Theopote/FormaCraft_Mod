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
}
