package com.formacraft.common.component.socket.place;

import com.formacraft.client.tool.placement.PlacementContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * PlacementContextAdapter（放置上下文适配器）：将 ComponentInstanceTransform 转换为 PlacementContext。
 */
public final class PlacementContextAdapter {
    private PlacementContextAdapter() {}

    /**
     * 从 ComponentInstanceTransform 创建 PlacementContext
     * 
     * @param transform 组件实例变换
     * @return PlacementContext
     */
    public static PlacementContext fromTransform(ComponentInstanceTransform transform) {
        if (transform == null) {
            return new PlacementContext(BlockPos.ORIGIN, Direction.SOUTH);
        }

        PlacementContext ctx = new PlacementContext(transform.origin, transform.facing);
        
        // 根据 facing 推断上下文信息
        if (transform.facing != null) {
            // 判断是否是墙面
            if (transform.facing.getAxis().isHorizontal()) {
                ctx.isWall = true;
                ctx.outwardNormal = transform.facing;
            } else if (transform.facing == Direction.UP) {
                ctx.isFloor = true;
            } else if (transform.facing == Direction.DOWN) {
                ctx.isRoof = true;
            }
        }

        // 默认设为 exterior（v1 简化）
        ctx.isExterior = true;

        return ctx;
    }
}
