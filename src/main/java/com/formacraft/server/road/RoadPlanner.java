package com.formacraft.server.road;

import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * RoadPlanner (v1):
 * A deterministic "smart road" builder:
 * - A* on XZ with terrain-sampled Y
 * - optional headroom clearing
 * - simple stairs on dy=±1 and simple bridge deck on water/gap
 *
 * Output: List<PlannedBlock> (Preview/Undo/Apply compatible).
 */
public final class RoadPlanner {
    private RoadPlanner() {}

    public record Config(
            int width,
            int clearHeight,
            int maxStep,
            int maxSearch,
            int stepPenalty,
            int localSlopePenalty,
            int bridgePenalty,
            BlockState road,
            BlockState border,
            boolean useBorder,
            BlockState bridgeDeck,
            BlockState bridgeRail
    ) {
        public static Config defaults() {
            return new Config(
                    3,
                    2,
                    1,
                    12000,
                    12,
                    2,
                    6,
                    Blocks.GRAVEL.getDefaultState(),
                    Blocks.COBBLESTONE.getDefaultState(),
                    false,
                    Blocks.OAK_PLANKS.getDefaultState(),
                    Blocks.OAK_FENCE.getDefaultState()
            );
        }
    }

    public static List<PlannedBlock> build(ServerWorld world, BlockPos start, BlockPos end, Config cfg) {
        if (world == null || start == null || end == null) return List.of();
        Config c = (cfg != null) ? cfg : Config.defaults();

        RoadSurfaceAnalyzer analyzer = new RoadSurfaceAnalyzer(world, c.clearHeight, c.maxStep);
        List<BlockPos> path = RoadAStar.findPath(start, end, analyzer, c.maxSearch, c.stepPenalty, c.localSlopePenalty, c.bridgePenalty);
        if (path.isEmpty()) return List.of();

        return RoadDecorator.decorate(world, path, c.width, c.clearHeight, c.road, c.border, c.useBorder, c.bridgeDeck, c.bridgeRail);
    }
}


