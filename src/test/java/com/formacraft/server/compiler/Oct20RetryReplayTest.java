package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class Oct20RetryReplayTest {
    @BeforeAll static void setup() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @TestFactory Stream<DynamicTest> actualPlans() {
        return IntStream.rangeClosed(2,8).mapToObj(i -> DynamicTest.dynamicTest("retry plan "+i, () -> {
            var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct20_retry_"+i+".normalized.json")));
            var patches=ComponentPlanCompiler.compile(plan);
            assertFalse(patches.isEmpty(),String.valueOf(AssemblyCompileDiagnostics.get()));
            assertFalse(AssemblyCompileDiagnostics.hasGap(),String.valueOf(AssemblyCompileDiagnostics.get()));
            if (i==3) {
                var blocks=com.formacraft.test.PatchTestSnapshot.blocks(patches);
                for(int x:new int[]{9,10}) for(int y:new int[]{5,6,7})
                    assertNull(blocks.get(new Vec3i(x,y,0)),"upper platform must open into the second storey");
                assertNotNull(blocks.get(new Vec3i(9,4,-1)),"deck reaches the doorway");
            }
            if (i==6) {
                var finished=com.formacraft.test.PatchTestSnapshot.blocks(patches);
                assertTrue(finished.values().stream().anyMatch(block->block.contains("leaves")),"garden greenery must produce actual blocks");
                assertNull(finished.get(new Vec3i(-17,22,0)),"facade completion must not restore a full-height outer shell");
                assertNull(finished.get(new Vec3i(0,22,-14)),"upper facade stays within the retreating body");
                var body=plan.components().stream().filter(c->"MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
                var bodyPatches=new com.formacraft.common.generation.component.impl.MassMainGenerator().generate(
                        new com.formacraft.common.compiler.semantic.SemanticComponent("MASS_MAIN",null,body));
                var blocks=com.formacraft.test.PatchTestSnapshot.blocks(bodyPatches);
                assertNotNull(blocks.get(new Vec3i(-17,2,-14)),"rectangular base corner exists");
                assertNull(blocks.get(new Vec3i(-17,22,-14)),"upper storeys retreat from the base");
                assertNotNull(blocks.get(new Vec3i(-9,22,-6)),"top tier uses its declared smaller footprint");
            }
        }));
    }
}
