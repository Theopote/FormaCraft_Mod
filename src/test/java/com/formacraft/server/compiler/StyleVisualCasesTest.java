package com.formacraft.server.compiler;

import com.formacraft.server.command.StyleVisualCases;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class StyleVisualCasesTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    static java.util.stream.Stream<String> cases() { return StyleVisualCases.IDS.stream(); }

    @ParameterizedTest @MethodSource("cases")
    void fixedSamplesReplayWithMaterialsAndOpenings(String id) throws Exception {
        var plan = StyleVisualCases.load(id);
        var shifted = StyleVisualCases.load(id, new BlockPos(100, -60, 0));
        assertEquals(new com.formacraft.common.llm.dto.Vec3i(100, -60, 0), shifted.anchor());
        assertEquals(plan.components(), shifted.components());
        var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        var blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertEquals(blocks, PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(
                StyleVisualCases.load(id), BlockPos.ORIGIN, null, null, false)));
        for (var component : plan.components()) {
            int x = component.relativePosition().x();
            var local = blocks.entrySet().stream().filter(e -> e.getKey().x() >= x && e.getKey().x() < x + 15).toList();
            String wall = component.params().get("wall_block").toString();
            String roof = component.params().get("roof_block").toString();
            assertTrue(local.stream().anyMatch(e -> e.getKey().y() > 0 && e.getKey().y() < 10 && e.getValue().equals(wall)), id + " wall");
            assertTrue(local.stream().anyMatch(e -> e.getKey().y() >= 10 && e.getValue().equals(roof)), id + " roof");
            assertTrue(local.stream().anyMatch(e -> e.getValue().contains("glass")), id + " windows");
            assertFalse(blocks.containsKey(new com.formacraft.common.llm.dto.Vec3i(x + 7, 1, 0)), id + " entrance");
        }
    }
}
