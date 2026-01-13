package com.formacraft.client.tool.placement;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * PlacementContext（运行时空间上下文）：ComponentTool 与世界之间的桥梁。
 */
public class PlacementContext {
    public final BlockPos targetPos;
    public final Direction hitFace;

    public boolean isWall;
    public boolean isFloor;
    public boolean isRoof;
    public boolean isEdge;
    public boolean isCorner;

    public boolean isInterior;
    public boolean isExterior;

    /** 派生方向：墙外法线/外法线 */
    public Direction outwardNormal;
    /** 派生方向：沿边缘的切线方向（栏杆等） */
    public Direction edgeDirection;

    public PlacementContext(BlockPos pos, Direction face) {
        this.targetPos = pos;
        this.hitFace = face;
    }
}

