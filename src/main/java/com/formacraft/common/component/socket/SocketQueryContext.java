package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * SocketQueryContext（Socket 查询上下文）。
 * <p>
 * 这是把 Selection/Outline/Path/Anchor 等输入统一封装起来（v1 足够用）。
 */
public final class SocketQueryContext {
    /** 用于"就近收集 sockets"的中心点（通常是鼠标 hit、或 anchor） */
    public Vec3d focus = Vec3d.ZERO;

    /** 收集半径（方块/近似） */
    public int radius = 32;

    /** 选区盒（如果存在） */
    public BlockPos selectionMin;
    public BlockPos selectionMax;

    /** 轮廓多边形（世界坐标点，XZ 平面；y 可取 anchorY 或地形采样） */
    public final List<Vec3d> outlinePolygon = new ArrayList<>();

    /** 路径（多段 polyline，每段是点序列） */
    public final List<List<Vec3d>> paths = new ArrayList<>();

    /** v1：是否需要洞口 socket */
    public boolean includeOpenings = true;

    /** v1：洞口扫描参数（越大越慢） */
    public int openingMaxScan = 24; // 在 wall bounds 内最多扫描多少格宽/高

    /** v1：洞口最小尺寸 */
    public int openingMinW = 1;
    public int openingMinH = 2;

    /** v1：洞口最大尺寸 */
    public int openingMaxW = 6;
    public int openingMaxH = 8;
}
