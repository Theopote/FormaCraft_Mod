package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HousePromptRegressionTest {
    @Test void explicitFlightExecutesAfterLaterFloorAndKeepsExactEvenWidth() {
        MinecraftRegistryTestBootstrap.initialize();
        var stair = new com.formacraft.common.llm.dto.Component("STRUCTURE", null,
            new com.formacraft.common.llm.dto.Vec3i(1, 0, 1), new com.formacraft.common.llm.dto.Dimensions(5, 2, 4),
            List.of("stair:straight_single_run"), Map.of("from", Map.of("x",0,"y",0,"z",0),
                "to",Map.of("x",3,"y",3,"z",0),"width",2,"stairs","minecraft:oak_stairs",
                "floor","minecraft:oak_planks","landing_length",2));
        var plate = new com.formacraft.common.llm.dto.Component("MASS_SECONDARY", null,
            new com.formacraft.common.llm.dto.Vec3i(0, 3, 0), new com.formacraft.common.llm.dto.Dimensions(8, 5, 6),
            List.of(), Map.of("anchor_mode","min_corner","extrude_mode","plate","material","oak_planks"));
        var plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build)
            .components(List.of(stair, plate)).build();
        var result = ComponentPlanCompiler.compileWithCirculation(plan, null, null, null, false);
        assertFalse(result.patches().isEmpty(), String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get())); assertEquals(1, result.circulation().size());
        var flight = result.circulation().getFirst();
        assertEquals(Set.of(0,1), flight.clearance().stream().map(BlockPos::getZ).collect(java.util.stream.Collectors.toSet()));
        assertTrue(flight.clearance().contains(new BlockPos(3,3,1)), "Later slab must be carved");
    }
    @Test void unsupportedNarrativeStairRejectsWholePlan() {
        var c = new com.formacraft.common.llm.dto.Component("STRUCTURE", null,
            new com.formacraft.common.llm.dto.Vec3i(0,0,0), new com.formacraft.common.llm.dto.Dimensions(3,9,6),
            List.of("stair:spiral"), Map.of());
        var plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(c)).build();
        assertTrue(ComponentPlanCompiler.compile(plan).isEmpty());
        assertEquals("E_STAIR_COMPONENT_INVALID", com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
    }
    @Test void tooSteepFlightRejectsWholePlan() {
        var c = new com.formacraft.common.llm.dto.Component("STRUCTURE", null,
            new com.formacraft.common.llm.dto.Vec3i(0,0,0), new com.formacraft.common.llm.dto.Dimensions(3,9,6),
            List.of("stair:straight_single_run"), Map.of("from", Map.of("x",0,"y",0,"z",0),"to",Map.of("x",1,"y",5,"z",0)));
        var plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(c)).build();
        assertTrue(ComponentPlanCompiler.compile(plan).isEmpty());
        assertEquals("E_STAIR_COMPONENT_INVALID", com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
    }
    @Test void loggedHouseHasContinuousSideWalls() throws Exception {
        MinecraftRegistryTestBootstrap.initialize();
        String json;
        try (var input = getClass().getResourceAsStream("/regressions/two-storey-house.json")) {
            json = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        var plan = com.formacraft.common.llm.parser.LlmPlanParser.parse(json);
        var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        var cells = new HashMap<BlockPos, BlockPatch>();
        for (var p : patches) cells.put(new BlockPos(p.dx(), p.dy(), p.dz()), p);
        var missing = new ArrayList<BlockPos>();
        for (int y = 1; y <= 11; y++) for (int z = 0; z < 13; z++) {
            var pos = new BlockPos(0, y, z);
            var p = cells.get(pos);
            if (p == null || BlockPatch.REMOVE.equals(p.action()) || "minecraft:air".equals(p.targetBlock())) missing.add(pos);
        }
        assertTrue(missing.isEmpty(), "Missing side wall cells: " + missing);
        assertEquals("minecraft:stone_bricks", cells.get(new BlockPos(0, 3, 5)).targetBlock(), "Decoration must preserve stone-brick walls");
        assertFalse(cells.get(new BlockPos(0, 2, 5)).targetBlock().contains("slab"), "Plinth must not leave a half-block gap under the wall");
        assertTrue(patches.stream().anyMatch(p -> p.targetBlock() != null && p.targetBlock().startsWith("minecraft:oak_stairs[")), "Logged stair must execute");
        var compilation = ComponentPlanCompiler.compileWithCirculation(plan, BlockPos.ORIGIN, null, null, false);
        assertEquals(1, compilation.circulation().size());
        var flight = compilation.circulation().getFirst();
        for (var pos : flight.clearance()) assertEquals(BlockPatch.REMOVE, cells.get(pos).action(), "Headroom at " + pos);
        for (int x = 1; x <= 3; x++) for (int z = 7; z <= 9; z++)
            assertEquals("minecraft:oak_planks", cells.get(new BlockPos(x, 6, z)).targetBlock(), "Top landing must meet second floor");
        assertEquals("minecraft:oak_planks", cells.get(new BlockPos(5, 6, 5)).targetBlock());
        assertEquals(BlockPatch.REMOVE, cells.get(new BlockPos(2, 6, 5)).action(), "Stair must cut the second-floor slab");
        assertFalse(cells.containsKey(new BlockPos(5, 7, 5)), "Floor plate must not fill upper-floor rooms");
    }
}
