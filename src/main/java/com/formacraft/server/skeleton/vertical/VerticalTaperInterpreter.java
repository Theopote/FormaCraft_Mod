package com.formacraft.server.skeleton.vertical;

import com.formacraft.common.skeleton.vertical.VerticalTaperPlan;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * VerticalTaperInterpreter: interprets a VerticalTaperPlan into "four-corner legs + braces + platforms + spire".
 * This is reusable for Eiffel-like towers and other vertical taper archetypes.
 */
public final class VerticalTaperInterpreter implements SkeletonInterpreter<VerticalTaperPlan> {
    private final BlockState leg;
    private final BlockState brace;
    private final BlockState platform;
    private final BlockState rail;
    private final BlockState spire;

    public VerticalTaperInterpreter(BlockState leg, BlockState brace, BlockState platform, BlockState rail, BlockState spire) {
        this.leg = leg;
        this.brace = brace;
        this.platform = platform;
        this.rail = rail;
        this.spire = spire;
    }

    @Override
    public List<PlannedBlock> interpret(VerticalTaperPlan plan, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>(Math.max(2000, plan.height * plan.baseHalf * 6));

        for (int y = 0; y <= plan.height; y++) {
            int half = plan.halfByY[y];
            int thickness = (y < plan.height * 0.18) ? 2 : 1;
            placeLegs(blocks, origin, half, y, thickness);
            if (y % (plan.refined ? 4 : 6) == 0) {
                placeBraces(blocks, origin, half, y);
            }
        }

        // platforms
        for (int py : plan.platformsY) {
            if (py < 0 || py > plan.height) continue;
            int half = plan.halfByY[py];
            int r = Math.min(plan.baseHalf, half + 3);
            placePlatform(blocks, origin, r, py);
            placeRail(blocks, origin, r, py + 1);
        }

        // spire
        for (int y = plan.spireStartY; y <= plan.spireEndY; y++) {
            blocks.add(new PlannedBlock(origin.add(0, y, 0), spire));
        }
        blocks.add(new PlannedBlock(origin.add(0, plan.spireEndY + 1, 0), Blocks.LIGHTNING_ROD.getDefaultState()));
        if (plan.refined) {
            blocks.add(new PlannedBlock(origin.add(1, plan.height + 3, 0), Blocks.LANTERN.getDefaultState()));
            blocks.add(new PlannedBlock(origin.add(-1, plan.height + 3, 0), Blocks.LANTERN.getDefaultState()));
        }

        return blocks;
    }

    private void placeLegs(List<PlannedBlock> blocks, BlockPos origin, int half, int y, int thickness) {
        int[] s = new int[]{-1, 1};
        for (int sx : s) {
            for (int sz : s) {
                int x = sx * half;
                int z = sz * half;
                blocks.add(new PlannedBlock(origin.add(x, y, z), leg));
                if (thickness >= 2) {
                    blocks.add(new PlannedBlock(origin.add(x - sx, y, z), leg));
                    blocks.add(new PlannedBlock(origin.add(x, y, z - sz), leg));
                }
            }
        }
    }

    private void placeBraces(List<PlannedBlock> blocks, BlockPos origin, int half, int y) {
        for (int x = -half + 1; x <= half - 1; x++) {
            blocks.add(new PlannedBlock(origin.add(x, y, -half), brace));
            blocks.add(new PlannedBlock(origin.add(x, y, half), brace));
        }
        for (int z = -half + 1; z <= half - 1; z++) {
            blocks.add(new PlannedBlock(origin.add(-half, y, z), brace));
            blocks.add(new PlannedBlock(origin.add(half, y, z), brace));
        }
    }

    private void placePlatform(List<PlannedBlock> blocks, BlockPos origin, int r, int y) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                blocks.add(new PlannedBlock(origin.add(x, y, z), platform));
            }
        }
    }

    private void placeRail(List<PlannedBlock> blocks, BlockPos origin, int r, int y) {
        for (int x = -r; x <= r; x++) {
            blocks.add(new PlannedBlock(origin.add(x, y, -r), rail));
            blocks.add(new PlannedBlock(origin.add(x, y, r), rail));
        }
        for (int z = -r; z <= r; z++) {
            blocks.add(new PlannedBlock(origin.add(-r, y, z), rail));
            blocks.add(new PlannedBlock(origin.add(r, y, z), rail));
        }
    }
}


