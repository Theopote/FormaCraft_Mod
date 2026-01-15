package com.formacraft.common.skeleton.socket;

import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.component.socket.SocketType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * SkeletonSocketProfile（骨架的 Socket 供给能力）。
 * <p>
 * 核心思想：
 * - Skeleton 不同类型，会产出不同 Socket 组合与密度
 * - 城墙：EDGE_OUTER / WALL_SURFACE / WALL_OPENING（高窗/射孔）
 * - 塔楼：WALL_SURFACE / ROOF_RIDGE / EDGE_OUTER / WALL_OPENING（小窗）
 * - 道路：FLOOR_SURFACE / EDGE_OUTER（路灯、栏杆）
 * - 屋顶：ROOF_SLOPE / ROOF_RIDGE（老虎窗、脊兽、烟囱）
 */
public final class SkeletonSocketProfile {
    public final SkeletonType skeletonType;
    public final Set<SocketType> providedSocketTypes;

    /** 每种 socket 的默认"密度/生成参数" */
    public final Map<SocketType, SocketDensity> density = new EnumMap<>(SocketType.class);

    public SkeletonSocketProfile(SkeletonType type, Set<SocketType> provided) {
        this.skeletonType = type;
        this.providedSocketTypes = EnumSet.copyOf(provided);
    }

    /**
     * 设置 Socket 密度
     */
    public SkeletonSocketProfile density(SocketType t, SocketDensity d) {
        density.put(t, d);
        return this;
    }

    /**
     * 创建城墙的 Socket Profile
     */
    public static SkeletonSocketProfile forCastleWall() {
        return new SkeletonSocketProfile(
                SkeletonType.PATH_POLYLINE,
                EnumSet.of(SocketType.WALL_SURFACE, SocketType.EDGE_OUTER, SocketType.WALL_OPENING)
        ).density(SocketType.WALL_OPENING, SocketDensity.openings(0.12, 2, 1)); // 比例、宽、高（粗略）
    }

    /**
     * 创建塔楼的 Socket Profile
     */
    public static SkeletonSocketProfile forTower() {
        return new SkeletonSocketProfile(
                SkeletonType.VERTICAL_TAPER,
                EnumSet.of(SocketType.WALL_SURFACE, SocketType.WALL_OPENING, SocketType.ROOF_RIDGE, SocketType.EDGE_OUTER)
        ).density(SocketType.WALL_OPENING, SocketDensity.openings(0.08, 1, 2));
    }

    /**
     * 创建道路的 Socket Profile
     */
    public static SkeletonSocketProfile forRoad() {
        return new SkeletonSocketProfile(
                SkeletonType.PATH_POLYLINE,
                EnumSet.of(SocketType.FLOOR_SURFACE, SocketType.EDGE_OUTER)
        ).density(SocketType.EDGE_OUTER, SocketDensity.sparse(0.15)); // 路灯、栏杆
    }

    /**
     * 创建屋顶的 Socket Profile
     */
    public static SkeletonSocketProfile forRoof() {
        return new SkeletonSocketProfile(
                SkeletonType.PATH_POLYLINE, // 或 VERTICAL_STACK
                EnumSet.of(SocketType.ROOF_SLOPE, SocketType.ROOF_RIDGE)
        ).density(SocketType.ROOF_RIDGE, SocketDensity.sparse(0.3)); // 脊兽、烟囱
    }
}
