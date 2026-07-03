package com.formacraft.server.skeleton.radial;

import com.formacraft.common.skeleton.radial.*;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Interprets RadialPlan primitives into PlannedBlocks.
 * Materials are provided via role->BlockState mapping.
 */
public final class RadialPrimitiveInterpreter implements SkeletonInterpreter<RadialPlan> {
    private final EnumMap<RadialRole, BlockState> palette;

    public RadialPrimitiveInterpreter(EnumMap<RadialRole, BlockState> palette) {
        this.palette = palette;
    }

    @Override
    public List<PlannedBlock> interpret(RadialPlan plan, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> out = new ArrayList<>(Math.max(2000, plan.primitives.size() * 400));
        for (RadialPrimitive p : plan.primitives) {
            BlockState s = palette.get(p.role);
            if (s == null) continue;
            switch (p.kind) {
                case DISK_FILL -> fillDisk(out, origin, p.outerRadius, p.y0, s);
                case ANNULUS_FILL -> fillAnnulus(out, origin, p.outerRadius, p.innerRadius, p.y0, s);
                case RING_OUTLINE -> ring(out, origin, p.outerRadius, p.y0, s);
                case CYLINDER_SHELL -> cylinderShell(out, origin, p.outerRadius, p.y0, p.y1, s);
            }
        }
        return out;
    }

    private static void fillDisk(List<PlannedBlock> out, BlockPos c, int r, int y, BlockState s) {
        int r2 = r * r;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= r2) {
                    BlockPos p = c.add(x, y, z);
                    if (BuildConstraintContext.allow(p)) out.add(new PlannedBlock(p, s));
                }
            }
        }
    }

    private static void fillAnnulus(List<PlannedBlock> out, BlockPos c, int rOuter, int rInner, int y, BlockState s) {
        int ro2 = rOuter * rOuter;
        int ri2 = rInner * rInner;
        for (int x = -rOuter; x <= rOuter; x++) {
            for (int z = -rOuter; z <= rOuter; z++) {
                int d2 = x * x + z * z;
                if (d2 <= ro2 && d2 >= ri2) {
                    BlockPos p = c.add(x, y, z);
                    if (BuildConstraintContext.allow(p)) out.add(new PlannedBlock(p, s));
                }
            }
        }
    }

    private static void ring(List<PlannedBlock> out, BlockPos c, int r, int y, BlockState s) {
        int r2 = r * r;
        int ri2 = Math.max(0, (r - 1) * (r - 1));
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int d2 = x * x + z * z;
                if (d2 <= r2 && d2 >= ri2) {
                    BlockPos p = c.add(x, y, z);
                    if (BuildConstraintContext.allow(p)) out.add(new PlannedBlock(p, s));
                }
            }
        }
    }

    private static void cylinderShell(List<PlannedBlock> out, BlockPos c, int r, int y0, int y1, BlockState s) {
        int lo = Math.min(y0, y1);
        int hi = Math.max(y0, y1);
        for (int y = lo; y <= hi; y++) {
            ring(out, c, r, y, s);
        }
    }
}


