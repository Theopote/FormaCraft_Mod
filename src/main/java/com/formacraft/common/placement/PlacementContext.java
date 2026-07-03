package com.formacraft.common.placement;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 放置上下文 DTO：ComponentTool 与编译器之间的桥梁，不依赖 client 包。
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

    /** 派生方向：墙外法线 */
    public Direction outwardNormal;
    /** 派生方向：沿边缘的切线方向（栏杆等） */
    public Direction edgeDirection;

    public PlacementContext(BlockPos pos, Direction face) {
        this.targetPos = pos;
        this.hitFace = face;
    }
}
