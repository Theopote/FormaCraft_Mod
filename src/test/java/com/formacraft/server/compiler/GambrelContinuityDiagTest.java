package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hosted gambrel: facade overwrite of the eave seat must not make continuity
 * audit see a false disconnected_slope_rows gap.
 */
class GambrelContinuityDiagTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }

    @Test void rawHostedGambrelFractionsAreContinuous() {
        var roof = new Component("ROOF","hall",new Vec3i(0,9,0),new Dimensions(13,17,13),List.of(),
                Map.of("roof_type","gambrel","roof_block","minecraft:hay_block",
                        "gambrel_break_run_fraction",0.5,"gambrel_break_rise_fraction",0.75,
                        "component_id","roof_main","host_id","alpine_chalet",
                        "overhang",0));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,patches,BlockPos.ORIGIN,Set.of()).isEmpty());
    }

    @Test void hostedGambrelCompileSurvivesFacadeEaveOverwrite() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        var document = mapper.readTree(fixture.toFile());
        var roofParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
        roofParams.put("gambrel_break_run_fraction", 0.5).put("gambrel_break_rise_fraction", 0.75);
        var plan = mapper.treeToValue(document, LlmPlan.class);
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan, new BlockPos(0, 64, 0), null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> "continuity must tolerate facade eave overwrite: " + AssemblyCompileDiagnostics.get());
        assertFalse(patches.isEmpty());
        assertTrue(patches.stream().anyMatch(p -> "minecraft:hay_block".equals(p.targetBlock())));
    }
}
