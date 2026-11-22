package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.generation.component.impl.StraightStairComponentGenerator;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class Oct20AccessReplayTest {
    @BeforeAll static void setup() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void incompatibleExplicitTreadReportsAGap() {
        assertInvalidTread("minecraft:iron_block");
        assertInvalidTread("minecraft:cracked_deepslate_brick_stairs");
    }

    private void assertInvalidTread(String material) {
        var stair = new Component("STRUCTURE", "flight", new Vec3i(0, 0, 0),
                new Dimensions(3, 4, 3), List.of("stair:straight_single_run"), Map.of(
                "stairs", material, "floor", "minecraft:stone",
                "from", Map.of("x", 0, "y", 0, "z", 0), "to", Map.of("x", 0, "y", 2, "z", 3)));
        try {
            var patches = StraightStairComponentGenerator.generate(new SemanticComponent("STRUCTURE", null, stair));
            assertTrue(patches.isEmpty());
            assertEquals("E_STAIR_COMPONENT_INVALID", AssemblyCompileDiagnostics.get().code());
        } finally {
            AssemblyCompileDiagnostics.clear();
        }
    }

    @Test void straightStairsUseFloorFamilyOnlyWhenTreadIsOmitted() {
        var params = new HashMap<String, Object>(Map.of(
                "floor", "minecraft:smooth_quartz",
                "from", Map.of("x", 0, "y", 0, "z", 0),
                "to", Map.of("x", 0, "y", 2, "z", 3)));
        for (String expected : List.of("smooth_quartz_stairs", "oak_stairs")) {
            var stair = new Component("STAIR_SYSTEM", "flight", new Vec3i(0, 0, 0),
                    new Dimensions(3, 4, 3), List.of(), Map.copyOf(params));
            var patches = StraightStairComponentGenerator.generate(new SemanticComponent("STAIR_SYSTEM", null, stair));
            assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            assertTrue(patches.stream().anyMatch(p -> p.targetBlock() != null
                    && p.targetBlock().contains(expected)
                    && (!expected.equals("oak_stairs") || p.targetBlock().contains("waterlogged=true"))));
            params.put("stairs", "minecraft:oak_stairs[waterlogged=true]");
        }
    }

    @Test void floorWithoutMatchingTreadRequiresExplicitStairs() {
        var params = new HashMap<String, Object>(Map.of(
                "floor", "minecraft:white_concrete",
                "from", Map.of("x", 0, "y", 0, "z", 0),
                "to", Map.of("x", 0, "y", 2, "z", 3)));
        var stair = new Component("STAIR_SYSTEM", "flight", new Vec3i(0, 0, 0),
                new Dimensions(3, 4, 3), List.of(), params);
        try {
            var semantic = new SemanticComponent("STAIR_SYSTEM", null, stair);
            assertTrue(StraightStairComponentGenerator.generate(semantic).isEmpty());
            var gap = AssemblyCompileDiagnostics.get();
            assertNotNull(gap);
            assertEquals("E_STAIR_COMPONENT_INVALID", gap.code());
            assertTrue(gap.message().contains("minecraft:white_concrete"));
            AssemblyCompileDiagnostics.clear();
            params.put("stairs", "minecraft:oak_stairs");
            var explicitStair = new Component("STAIR_SYSTEM", "flight", stair.relativePosition(),
                    stair.dimensions(), List.of(), Map.copyOf(params));
            var patches = StraightStairComponentGenerator.generate(
                    new SemanticComponent("STAIR_SYSTEM", null, explicitStair));
            assertTrue(patches.stream().anyMatch(p -> p.targetBlock() != null
                    && p.targetBlock().contains("oak_stairs")));
            assertFalse(AssemblyCompileDiagnostics.hasGap());
        } finally {
            AssemblyCompileDiagnostics.clear();
        }
    }

    @TestFactory Stream<DynamicTest> loggedPlans() {
        // Plan 2 historical capture stays height=8 (coplanar pyramid conflict). Compile success
        // uses the explicit repaired fixture — never rewrite the immutable LLM raw capture.
        return IntStream.of(1,2,3,5,6,7,8).mapToObj(i -> DynamicTest.dynamicTest("oct20 plan "+i, () -> {
            String path = i == 2
                    ? "python_backend/tests/fixtures/complex_build_retest/oct20_access_2_repaired.normalized.json"
                    : "python_backend/tests/fixtures/complex_build_retest/oct20_access_"+i+".normalized.json";
            var plan=com.formacraft.common.llm.dto.TransitionalSatellitePlans.ensureAllowIfAbsent(
                    LlmPlanParser.parse(Files.readString(Path.of(path))));
            var patches=ComponentPlanCompiler.compile(plan);
            assertFalse(patches.isEmpty(),String.valueOf(AssemblyCompileDiagnostics.get()));
            assertFalse(AssemblyCompileDiagnostics.hasGap(),String.valueOf(AssemblyCompileDiagnostics.get()));
            if (i == 6) {
                var blocks=com.formacraft.test.PatchTestSnapshot.blocks(patches);
                assertNotNull(blocks.get(new com.formacraft.common.llm.dto.Vec3i(-8,6,12)), "hotel deck remains raised at its authored position");
                assertNull(blocks.get(new com.formacraft.common.llm.dto.Vec3i(-8,1,12)), "deck must not become a buried foundation");
            }
            if (i == 5 || i == 6) {
                var stair=plan.components().stream().filter(c->Boolean.TRUE.equals(c.params().get("exterior"))).findFirst().orElseThrow();
                var flight=com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.generate(
                        new com.formacraft.common.compiler.semantic.SemanticComponent("STRUCTURE",null,stair));
                int level=i==5?4:6;
                assertEquals(level,flight.stream().filter(p->"place".equals(p.action())).mapToInt(p->p.dy()).max().orElseThrow());
                var blocks=com.formacraft.test.PatchTestSnapshot.blocks(patches);
                // The final tread and the next deck row must touch at the same level.
                for (int x : i==5 ? new int[]{1,2,3} : new int[]{2,1,0})
                    assertNotNull(blocks.get(new com.formacraft.common.llm.dto.Vec3i(x,level,i==5?-2:10)), "stair-to-deck connection has no missing row");
            }
        }));
    }

    @Test
    void historicalOct20Plan2WithoutRepairSurfacesFlatRoofCoverageGap() throws Exception {
        var plan = com.formacraft.common.llm.dto.TransitionalSatellitePlans.ensureAllowIfAbsent(
                LlmPlanParser.parse(Files.readString(Path.of(
                        "python_backend/tests/fixtures/complex_build_retest/oct20_access_2.normalized.json"))));
        AssemblyCompileDiagnostics.clear();
        ComponentPlanCompiler.compile(plan);
        assertTrue(AssemblyCompileDiagnostics.hasGap(),
                "immutable historical capture must not silently pass after geometry rewrite policy");
        assertEquals("E_FLAT_ROOF_COVERAGE", AssemblyCompileDiagnostics.get().code());
    }

    @Test void descendingDescriptionKeepsLandingAtRaisedEnd() throws Exception {
        var plan=com.formacraft.common.llm.dto.TransitionalSatellitePlans.ensureAllowIfAbsent(LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct20_access_5.json"))));
        var stair=plan.components().stream().filter(c->Boolean.TRUE.equals(c.params().get("exterior"))).findFirst().orElseThrow();
        var params=new java.util.HashMap<>(stair.params());
        params.put("from",stair.params().get("to"));
        params.put("to",stair.params().get("from"));
        var ascending=new com.formacraft.common.llm.dto.Component(stair.componentType(),stair.slotId(),stair.relativePosition(),stair.dimensions(),stair.features(),params);
        assertEquals(com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.generate(
                        new com.formacraft.common.compiler.semantic.SemanticComponent("STRUCTURE",null,ascending)),
                com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.generate(
                        new com.formacraft.common.compiler.semantic.SemanticComponent("STRUCTURE",null,stair)));
    }

    @Test void crossSlotStairMayCarveOnlyItsDeclaredReceivingDeck() {
        var deck=new com.formacraft.common.llm.dto.Component("TERRACE","destination",null,null,java.util.List.of(),java.util.Map.of("host_id","target"));
        var connected=new com.formacraft.common.llm.dto.Component("STRUCTURE","source",null,null,java.util.List.of("stair:straight_single_run"),java.util.Map.of("host_id","source","target_host_id","target"));
        var unrelated=new com.formacraft.common.llm.dto.Component("STRUCTURE","source",null,null,connected.features(),java.util.Map.of("host_id","source","target_host_id","other"));
        var index=new PatchOccupancyIndex();
        index.commit(deck,java.util.List.of(new com.formacraft.common.patch.BlockPatch("place",0,4,0,"minecraft:stone")));
        var carve=java.util.List.of(new com.formacraft.common.patch.BlockPatch("remove",0,4,0,"minecraft:air"));
        assertTrue(index.validate(connected,carve).isEmpty());
        assertTrue(index.validate(unrelated,carve).isPresent());
    }
}
