package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.BridgeWalkability;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole-building stair circulation samples. Local carve checks are not sufficient;
 * these assert final patches remain walkable at authored landings after compile.
 */
class StairWholeBuildingCirculationTest {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void oct18RaisedTowerLandingStaysStandableAfterCompile() throws Exception {
        var json = Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct18_3.normalized.json"));
        var plan = LlmPlanParser.parse(json);
        var compilation = ComponentPlanCompiler.compileWithCirculation(
                plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(compilation.patches().isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(compilation.circulation().isEmpty(), "exterior connector stairs must publish flights");
        var occ = BridgeWalkability.occupancyFromPatches(compilation.patches());
        assertTrue(WalkabilityGraph.canStand(occ, new Vec3i(0, 8, -26)),
                "raised tower landing must remain standable");
    }

    @Test
    void fidelityInteriorStairStillOpensUpperFloorBand() throws Exception {
        var json = Files.readString(Path.of("src/test/resources/plans/fidelity/stair_upper_landing_walkable.json"));
        var plan = LlmPlanParser.parse(json);
        var compilation = ComponentPlanCompiler.compileWithCirculation(
                plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(compilation.patches().isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(compilation.circulation().stream().anyMatch(f ->
                        f.clearance().stream().anyMatch(p -> p.getY() == 4)),
                "flight must open the upper floor band");
    }
}