package com.formacraft.server.skeleton.stack;

import com.formacraft.common.skeleton.stack.VerticalStackPlan;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * VerticalStackInterpreter: square stacked tower with eaves bands and a top spire.
 * This is meant to be reused by multiple pagoda-like archetypes.
 */
public final class VerticalStackInterpreter implements SkeletonInterpreter<VerticalStackPlan> {
    private final BlockState body;
    private final BlockState trim;
    private final BlockState eave;
    private final BlockState accent;
    private final boolean hollow;

    public VerticalStackInterpreter(BlockState body, BlockState trim, BlockState eave, BlockState accent, boolean hollow) {
        this.body = body;
        this.trim = trim;
        this.eave = eave;
        this.accent = accent;
        this.hollow = hollow;
    }

    @Override
    public List<PlannedBlock> interpret(VerticalStackPlan plan, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>(Math.max(5000, plan.levels.size() * 2000));

        for (int li = 0; li < plan.levels.size(); li++) {
            VerticalStackPlan.Level lvl = plan.levels.get(li);
            int half = lvl.half;

            int inner = Math.max(1, half - (plan.refined ? 2 : 3));
            boolean doHollow = hollow && half * 2 + 1 >= (plan.refined ? 11 : 13);

            for (int dy = 0; dy < lvl.height; dy++) {
                int y = lvl.y0 + dy;
                for (int x = -half; x <= half; x++) {
                    for (int z = -half; z <= half; z++) {
                        boolean edge = Math.abs(x) == half || Math.abs(z) == half;
                        boolean corner = Math.abs(x) == half && Math.abs(z) == half;
                        if (doHollow && !edge && Math.abs(x) <= inner && Math.abs(z) <= inner) {
                            continue;
                        }
                        BlockState s = body;
                        if (corner && (dy % 3 == 0)) s = accent;
                        blocks.add(new PlannedBlock(origin.add(x, y, z), s));
                    }
                }
            }

            // door opening at ground level only
            if (li == 0) {
                carveDoor(blocks, origin, plan.facing, lvl.y0 + 1, half * 2 + 1);
            }

            // eaves at top of each level
            ringSquare(blocks, origin, half + 1, lvl.eaveY, eave);
            ringSquare(blocks, origin, half, lvl.eaveY, trim);
        }

        // top spire
        int topY = plan.levels.get(plan.levels.size() - 1).eaveY + 2;
        blocks.add(new PlannedBlock(origin.add(0, topY, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, topY + 1, 0), Blocks.LIGHTNING_ROD.getDefaultState()));

        return blocks;
    }

    private static void ringSquare(List<PlannedBlock> blocks, BlockPos origin, int half, int y, BlockState s) {
        for (int x = -half; x <= half; x++) {
            blocks.add(new PlannedBlock(origin.add(x, y, -half), s));
            blocks.add(new PlannedBlock(origin.add(x, y, half), s));
        }
        for (int z = -half; z <= half; z++) {
            blocks.add(new PlannedBlock(origin.add(-half, y, z), s));
            blocks.add(new PlannedBlock(origin.add(half, y, z), s));
        }
    }

    private static void carveDoor(List<PlannedBlock> blocks, BlockPos origin, Direction facing, int y0, int w) {
        int half = w / 2;
        int x = 0;
        int z = 0;
        if (facing == Direction.SOUTH) z = half;
        if (facing == Direction.NORTH) z = -half;
        if (facing == Direction.EAST) x = half;
        if (facing == Direction.WEST) x = -half;

        for (int dy = 0; dy < 3; dy++) {
            blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z), Blocks.AIR.getDefaultState()));
            if (half >= 5) {
                if (facing == Direction.SOUTH || facing == Direction.NORTH) {
                    blocks.add(new PlannedBlock(origin.add(x + 1, y0 + dy, z), Blocks.AIR.getDefaultState()));
                    blocks.add(new PlannedBlock(origin.add(x - 1, y0 + dy, z), Blocks.AIR.getDefaultState()));
                } else {
                    blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z + 1), Blocks.AIR.getDefaultState()));
                    blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z - 1), Blocks.AIR.getDefaultState()));
                }
            }
        }
        blocks.add(new PlannedBlock(origin.add(x, y0, z), Blocks.OAK_DOOR.getDefaultState()));
    }
}


