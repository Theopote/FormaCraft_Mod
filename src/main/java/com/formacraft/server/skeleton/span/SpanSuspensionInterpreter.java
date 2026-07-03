package com.formacraft.server.skeleton.span;

import com.formacraft.common.skeleton.span.SpanSuspensionPlan;
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
 * Interprets SpanSuspensionPlan into PlannedBlocks.
 * This is a reusable "span suspension bridge" muscle layer.
 */
public final class SpanSuspensionInterpreter implements SkeletonInterpreter<SpanSuspensionPlan> {
    private final Direction facing;
    private final BlockState tower;
    private final BlockState deck;
    private final BlockState cable;
    private final BlockState hanger;
    private final BlockState rail;
    private final BlockState foundation;

    public SpanSuspensionInterpreter(Direction facing, BlockState tower, BlockState deck, BlockState cable, BlockState hanger, BlockState rail, BlockState foundation) {
        this.facing = facing;
        this.tower = tower;
        this.deck = deck;
        this.cable = cable;
        this.hanger = hanger;
        this.rail = rail;
        this.foundation = foundation;
    }

    @Override
    public List<PlannedBlock> interpret(SpanSuspensionPlan plan, BlockPos origin, ServerWorld world) {
        int span = plan.deckCenters.size() - 1;
        Direction right = facing.rotateYClockwise();
        int halfW = plan.deckHalfWidth;
        int z1 = plan.towerIndex1;
        int z2 = plan.towerIndex2;
        int towerH = plan.towerHeight;

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(12000, span * (halfW * 2 + 5) * 4));

        // 1) deck + rails
        for (int i = 0; i <= span; i++) {
            BlockPos c = plan.deckCenters.get(i);
            for (int w = -halfW; w <= halfW; w++) {
                blocks.add(new PlannedBlock(c.add(right.getOffsetX() * w, 0, right.getOffsetZ() * w), deck));
            }
            blocks.add(new PlannedBlock(c.add(right.getOffsetX() * (halfW + 1), 1, right.getOffsetZ() * (halfW + 1)), rail));
            blocks.add(new PlannedBlock(c.add(-right.getOffsetX() * (halfW + 1), 1, -right.getOffsetZ() * (halfW + 1)), rail));
        }

        // 2) towers
        buildTower(blocks, plan.deckCenters.get(z1), right, halfW, towerH, plan.refined);
        buildTower(blocks, plan.deckCenters.get(z2), right, halfW, towerH, plan.refined);

        // 3) main cables + hangers
        int cableOff = halfW + 2;
        int lx = right.getOffsetX() * cableOff;
        int lz = right.getOffsetZ() * cableOff;
        int rx = -lx;
        int rz = -lz;

        for (int i = 0; i <= span; i++) {
            BlockPos c = plan.deckCenters.get(i);
            int yCable = plan.cableY[i];
            blocks.add(new PlannedBlock(new BlockPos(c.getX() + lx, yCable, c.getZ() + lz), cable));
            blocks.add(new PlannedBlock(new BlockPos(c.getX() + rx, yCable, c.getZ() + rz), cable));

            if (i >= z1 && i <= z2 && (i % 2 == 0)) {
                int hangerTop = yCable - 1;
                int hangerBottom = c.getY() + 2;
                for (int y = hangerBottom; y <= hangerTop; y++) {
                    blocks.add(new PlannedBlock(new BlockPos(c.getX() + lx, y, c.getZ() + lz), hanger));
                    blocks.add(new PlannedBlock(new BlockPos(c.getX() + rx, y, c.getZ() + rz), hanger));
                }
            }
        }

        return blocks;
    }

    private void buildTower(List<PlannedBlock> blocks, BlockPos deckCenterAtTower, Direction right, int halfW, int towerH, boolean refined) {
        int off = halfW + 2;
        int lx = right.getOffsetX() * off;
        int lz = right.getOffsetZ() * off;
        int rx = -lx;
        int rz = -lz;

        int towerY0 = deckCenterAtTower.getY();

        // foundation pads
        for (int y = -3; y <= 0; y++) {
            blocks.add(new PlannedBlock(new BlockPos(deckCenterAtTower.getX() + lx, towerY0 + y, deckCenterAtTower.getZ() + lz), foundation));
            blocks.add(new PlannedBlock(new BlockPos(deckCenterAtTower.getX() + rx, towerY0 + y, deckCenterAtTower.getZ() + rz), foundation));
        }

        int legThick = refined ? 2 : 1;
        for (int y = 0; y <= towerH; y++) {
            blocks.add(new PlannedBlock(new BlockPos(deckCenterAtTower.getX() + lx, towerY0 + y, deckCenterAtTower.getZ() + lz), tower));
            blocks.add(new PlannedBlock(new BlockPos(deckCenterAtTower.getX() + rx, towerY0 + y, deckCenterAtTower.getZ() + rz), tower));
            if (legThick == 2) {
                blocks.add(new PlannedBlock(new BlockPos(deckCenterAtTower.getX() + lx + right.getOffsetX(), towerY0 + y, deckCenterAtTower.getZ() + lz + right.getOffsetZ()), tower));
                blocks.add(new PlannedBlock(new BlockPos(deckCenterAtTower.getX() + rx - right.getOffsetX(), towerY0 + y, deckCenterAtTower.getZ() + rz - right.getOffsetZ()), tower));
            }
            if (y == (int) Math.round(towerH * 0.35) || y == (int) Math.round(towerH * 0.72) || (refined && y == (int) Math.round(towerH * 0.52))) {
                int steps = (halfW + 2) * 2;
                for (int i = 0; i <= steps; i++) {
                    double t = (steps == 0) ? 0.0 : (i / (double) steps);
                    int x = (int) Math.round(lerp(deckCenterAtTower.getX() + lx, deckCenterAtTower.getX() + rx, t));
                    int z = (int) Math.round(lerp(deckCenterAtTower.getZ() + lz, deckCenterAtTower.getZ() + rz, t));
                    blocks.add(new PlannedBlock(new BlockPos(x, towerY0 + y, z), tower));
                }
            }
        }
        blocks.add(new PlannedBlock(new BlockPos(deckCenterAtTower.getX(), towerY0 + towerH + 1, deckCenterAtTower.getZ()), Blocks.LIGHTNING_ROD.getDefaultState()));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}


