package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.interior.BspFloorPlanGenerator;
import com.formacraft.server.interior.FloorPlanConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * Interior/floor-plan assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblyInteriorOps {
    private AssemblyInteriorOps() {}

    public interface Adapter {
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        int clamp(int v, int min, int max);
    }

    public static void applyBspFloorPlan(List<PlannedBlock> out,
                                         MetaAssemblyEngine.Context ctx,
                                         BlockPos origin,
                                         Map<String, Object> op,
                                         Adapter adapter) {
        int w = adapter.clamp(adapter.i(op.get("w"), 19), 7, 129);
        int d = adapter.clamp(adapter.i(op.get("d"), 19), 7, 129);
        int h = adapter.clamp(adapter.i(op.get("h"), 30), 8, 255);

        Object cfgObj = op.get("floor_plan_logic");
        if (cfgObj == null) cfgObj = op.get("config");
        if (cfgObj == null) cfgObj = op.get("floorPlanLogic");
        FloorPlanConfig fpc = FloorPlanConfig.fromExtra(cfgObj);
        if (fpc == null) return;

        BlockState coreWall = adapter.pick(ctx, op, "coreWall", "FRAME", 0xA55101L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState roomWall;
        if (fpc.partitionStyle != null && fpc.partitionStyle.contains("OPEN")) {
            roomWall = adapter.pick(ctx, op, "roomWallOpen", "PARTITION_WALL", 0xA55102L, Blocks.GLASS_PANE.getDefaultState());
        } else {
            roomWall = adapter.pick(ctx, op, "roomWall", "PARTITION_WALL", 0xA55103L, coreWall);
        }
        BlockState stairs = adapter.pick(ctx, op, "stairs", "STAIRS", 0xA55104L, Blocks.STONE_BRICK_STAIRS.getDefaultState());

        BspFloorPlanGenerator.apply(
                out,
                origin,
                ctx.world(),
                w,
                d,
                h,
                fpc,
                BspFloorPlanGenerator.Materials.of(coreWall, roomWall, stairs)
        );
    }
}
