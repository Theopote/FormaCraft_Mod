package com.formacraft.server.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict default: absent allow_compiler_satellites must still compile when the AI
 * plan already authors MASS + satellites (no transitional opt-in required).
 */
class StrictModeAuthoredPlanCompileTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void authoredEvalPlanCompilesWithoutTransitionalSatellites() throws Exception {
        var mapper = new ObjectMapper();
        LlmPlan plan = mapper.readValue(
                java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_desert_adobe_13.json").toFile(),
                LlmPlan.class);
        assertFalse(PlanMetaAccess.allowCompilerSatellites(plan),
                "fixture must stay on strict/default opt-in (absent → false)");

        var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
    }
}
