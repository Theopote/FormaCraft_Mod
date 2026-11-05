package com.formacraft.server.skeleton.stack;

import com.formacraft.common.skeleton.stack.VerticalStackPlan;
import com.formacraft.common.build.PlannedBlock;
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

            // Wall thickness 1–2; hollow when cavity ≥1 cell (width≥5 / half≥2).
            int wallThick = Math.max(1, Math.min(2, plan.refined ? 2 : 1));
            int inner = Math.max(0, half - wallThick);
            boolean doHollow = hollow && inner >= 1;

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

            // door opening at ground level only — tunnel through wallThick into hollow
            if (li == 0) {
                carveDoor(blocks, origin, plan.facing, lvl.y0 + 1, half * 2 + 1, wallThick);
            }

            // eaves at top of each level — trim sits on body top edge; outer eave attaches to trim
            int bodyTopY = lvl.y0 + lvl.height - 1;
            ringSquare(blocks, origin, half, bodyTopY, trim);
            ringSquare(blocks, origin, half, lvl.eaveY, trim);
            ringSquare(blocks, origin, half + 1, lvl.eaveY, eave);
        }

        // top spire
        int topY = plan.levels.get(plan.levels.size() - 1).eaveY + 2;
        blocks.add(new PlannedBlock(origin.add(0, topY, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, topY + 1, 0), Blocks.LIGHTNING_ROD.getDefaultState()));

        return blocks;
    }

    private static void ringSquare(List<PlannedBlock> blocks, BlockPos origin, int half, int y, BlockState s) {
        for (int x = -half; x <= half; x++) {
            blocks.add(new PlannedBlock(origin.add(x, y, -half), faceOut(s, x, -half)));
            blocks.add(new PlannedBlock(origin.add(x, y, half), faceOut(s, x, half)));
        }
        for (int z = -half; z <= half; z++) {
            blocks.add(new PlannedBlock(origin.add(-half, y, z), faceOut(s, -half, z)));
            blocks.add(new PlannedBlock(origin.add(half, y, z), faceOut(s, half, z)));
        }
    }

    private static BlockState faceOut(BlockState state, int x, int z) {
        if (state == null) return state;
        Direction out = com.formacraft.common.typology.detail.ChineseTypologyDetailUtil.outwardFromCenter(x, z);
        if (state.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
            return state.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, out);
        }
        if (state.contains(net.minecraft.state.property.Properties.FACING) && out.getAxis().isHorizontal()) {
            return state.with(net.minecraft.state.property.Properties.FACING, out);
        }
        return state;
    }

    private static void carveDoor(
            List<PlannedBlock> blocks, BlockPos origin, Direction facing, int y0, int w, int wallThick
    ) {
        int half = w / 2;
        int x = 0;
        int z = 0;
        if (facing == Direction.SOUTH) z = half;
        if (facing == Direction.NORTH) z = -half;
        if (facing == Direction.EAST) x = half;
        if (facing == Direction.WEST) x = -half;

        Direction inward = facing.getOpposite();
        int depth = Math.max(1, wallThick);
        for (int d = 0; d <= depth; d++) {
            int cx = x + inward.getOffsetX() * d;
            int cz = z + inward.getOffsetZ() * d;
            for (int dy = 0; dy < 3; dy++) {
                blocks.add(new PlannedBlock(origin.add(cx, y0 + dy, cz), Blocks.AIR.getDefaultState()));
                if (half >= 5 && d == 0) {
                    if (facing == Direction.SOUTH || facing == Direction.NORTH) {
                        blocks.add(new PlannedBlock(origin.add(cx + 1, y0 + dy, cz), Blocks.AIR.getDefaultState()));
                        blocks.add(new PlannedBlock(origin.add(cx - 1, y0 + dy, cz), Blocks.AIR.getDefaultState()));
                    } else {
                        blocks.add(new PlannedBlock(origin.add(cx, y0 + dy, cz + 1), Blocks.AIR.getDefaultState()));
                        blocks.add(new PlannedBlock(origin.add(cx, y0 + dy, cz - 1), Blocks.AIR.getDefaultState()));
                    }
                }
            }
        }
        blocks.add(new PlannedBlock(origin.add(x, y0, z), Blocks.OAK_DOOR.getDefaultState()));
    }
}


