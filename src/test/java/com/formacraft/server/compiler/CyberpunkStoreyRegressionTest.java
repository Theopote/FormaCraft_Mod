package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CyberpunkStoreyRegressionTest {
    @Test void realGamePlanRetainsUpperFloorsAndVerticalCirculation() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        try (var input = getClass().getResourceAsStream("/style_quality_20261115/cyberpunk_tower.json")) {
            assertNotNull(input);
            var plan = LlmPlanParser.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var normalized = SlotPrimaryAnchorRepair.apply(com.formacraft.common.llm.parser.LlmPlanAnchorNormalizer.normalize(plan));
            var body = normalized.components().stream().filter(c -> "MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
            var origin = ComponentPlanCompiler.resolveMassOrigin(body);
            var slot = ComponentPlanCompiler.indexSlots(normalized).get(body.slotId());
            int ox = origin.x() + (slot == null ? 0 : slot.anchor().x());
            int oy = origin.y() + (slot == null ? 0 : slot.anchor().y());
            int oz = origin.z() + (slot == null ? 0 : slot.anchor().z());
            var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
            assertFalse(patches.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
            var finalAt = new HashMap<BlockPos, BlockPatch>();
            for (var patch : patches) finalAt.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
            for (int level : List.of(4, 8, 12, 16)) {
                int occupied = 0;
                for (int x = 1; x < 11; x++) for (int z = 1; z < 11; z++)
                    if (GeneratedSurfaceCapture.occupied(finalAt.get(new BlockPos(ox + x, oy + level, oz + z)))) occupied++;
                assertTrue(occupied >= 60, "Floor " + level + " has only " + occupied + "/100 interior cells");
            }
            assertTrue(finalAt.values().stream().anyMatch(p -> GeneratedSurfaceCapture.occupied(p)
                    && p.targetBlock().contains("stairs") && p.dy() > oy + 8), "Upper storeys need real stair treads");
        }
    }
}
