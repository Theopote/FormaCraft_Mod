package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import com.formacraft.server.command.StyleVisualCases;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TrapezoidPlanCompileTest {
    @Test void galleryReplayJoinsALoftToBothModelAuthoredVolumes() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var plan = StyleVisualCases.load("loft_gallery");
        var volumes = plan.components().stream().filter(c -> "PRIMITIVE".equals(c.componentType())).toList();
        var volumePlan = LlmPlanTestFixtures.minimal(java.util.Map.of(), volumes);
        var volumeBlocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(volumePlan));
        assertEquals(24, volumeBlocks.keySet().stream().filter(pos -> pos.y() == 0 && pos.z() == 0).count());
        assertEquals(16, volumeBlocks.keySet().stream().filter(pos -> pos.y() == 0 && pos.z() == 13).count());

        var loft = plan.components().stream().filter(c -> "ASSEMBLY".equals(c.componentType())).findFirst().orElseThrow();
        var payload = loft.params().get("assembly");
        assertTrue(AssemblySpecValidator.validate(payload).stream()
                .noneMatch(issue -> issue.severity() == AssemblyValidationIssue.Severity.ERROR));
        var pos = loft.relativePosition();
        var origin = new BlockPos(pos.x(), pos.y(), pos.z());
        var ctx = new MetaAssemblyEngine.Context(null, origin, Direction.SOUTH, null);
        var roof = new MetaAssemblyEngine().executeGeometry(AssemblySpec.fromExtra(payload), ctx).stream()
                .map(block -> block.getPos()).collect(java.util.stream.Collectors.toSet());

        for (int z : new int[]{0, 13}) {
            int y = z == 0 ? 5 : 8;
            assertTrue(roof.contains(new BlockPos(12, y, z)));
            assertTrue(volumeBlocks.containsKey(new Vec3i(12, y - 1, z)));
        }
        assertTrue(roof.contains(new BlockPos(12, 6, 6)));
        assertTrue(roof.stream().allMatch(p -> p.getX() >= 0 && p.getX() < 24 && p.getZ() >= 0 && p.getZ() < 14));
    }

    @Test void canonicalPlanEmitsBothAuthoredEndWidths() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        try (var input = getClass().getResourceAsStream("/style_quality_20261115/trapezoid_volume.json")) {
            assertNotNull(input);
            var plan = LlmPlanParser.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            var result = ComponentPlanCompiler.compile(plan);
            assertFalse(result.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            var placed = result.stream().filter(p -> BlockPatch.PLACE.equals(p.action())).toList();
            assertTrue(placed.stream().allMatch(p -> "minecraft:smooth_quartz".equals(p.targetBlock())));
            for (int y = 2; y < 6; y++) {
                int level = y;
                assertEquals(9L, placed.stream().filter(p -> p.dy() == level && p.dz() == 4).count());
                assertEquals(4L, placed.stream().filter(p -> p.dy() == level && p.dz() == 10).count());
            }
        }
    }
}
