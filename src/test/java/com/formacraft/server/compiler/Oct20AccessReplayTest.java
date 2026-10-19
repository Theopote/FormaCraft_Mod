package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class Oct20AccessReplayTest {
    @BeforeAll static void setup() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @TestFactory Stream<DynamicTest> loggedPlans() {
        return IntStream.of(1,2,3,5,6,7,8).mapToObj(i -> DynamicTest.dynamicTest("oct20 plan "+i, () -> {
            var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct20_access_"+i+".normalized.json")));
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

    @Test void descendingDescriptionKeepsLandingAtRaisedEnd() throws Exception {
        var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct20_access_5.json")));
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
