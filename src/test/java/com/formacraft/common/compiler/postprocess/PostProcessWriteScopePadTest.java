package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PostProcessWriteScopePadTest {
    @Test
    void exteriorPadAllowsPlaceOneCellOutsideVolumeButNotTwo() {
        var volume = new PostProcessContext.BuildingVolume(
                "m0",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 10, 8, 10),
                4);
        PostProcessContext ctx = PostProcessContext.create(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true).build(),
                new BlockPos(0, 64, 0),
                List.of(volume));
        PostProcessWriteScope scope = PostProcessWriteScope.fromContext(ctx, Set.of());

        assertTrue(scope.allowsPlace(new BlockPos(5, 3, 5))); // interior
        assertTrue(scope.allowsPlace(new BlockPos(-1, 3, 5))); // +1 pad
        assertFalse(scope.allowsPlace(new BlockPos(-2, 3, 5))); // +2 rejected
        assertTrue(scope.allowsCarve(new BlockPos(5, 3, 5)));
        assertFalse(scope.allowsCarve(new BlockPos(-1, 3, 5))); // carve stays interior
    }

    @Test
    void seedOnlyModePadsPlaceNeighborhoodButNotCarve() {
        PostProcessContext ctx = PostProcessContext.create(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true).build(),
                new BlockPos(0, 64, 0),
                List.of());
        PostProcessWriteScope scope = PostProcessWriteScope.fromContext(ctx, Set.of(new BlockPos(0, 0, 0)));
        assertTrue(scope.allowsPlace(new BlockPos(0, 0, 0)));
        assertTrue(scope.allowsPlace(new BlockPos(1, 0, 0)));
        assertFalse(scope.allowsPlace(new BlockPos(2, 0, 0)));
        assertTrue(scope.allowsCarve(new BlockPos(0, 0, 0)));
        assertFalse(scope.allowsCarve(new BlockPos(1, 0, 0)));
    }
}
