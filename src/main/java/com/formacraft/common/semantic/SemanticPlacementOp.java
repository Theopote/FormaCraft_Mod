package com.formacraft.common.semantic;

import com.formacraft.common.geometry.GeometryIntent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * 语义放置指令
 * 
 * Generator 不再输出 minecraft:block_id，而输出：
 * - 位置 pos
 * - 朝向 facing（用于几何修饰）
 * - 语义部位 part（墙体、路面、栏杆…）
 * - 语义角色 role（FILL / EDGE / PILLAR / TRIM…）
 * - 几何意图 geometry（THIN / THICK / OVERHANG 等）
 * - 可选 tag（比如 "weathered"、"corner"、"gate"）
 */
public record SemanticPlacementOp(
        BlockPos pos,
        Direction facing,
        SemanticPart part,
        SemanticRole role,
        GeometryIntent geometry,
        Set<String> tags
) {
    /**
     * 基础构造：只有位置和部位
     */
    public static SemanticPlacementOp of(BlockPos pos, SemanticPart part) {
        return new SemanticPlacementOp(pos, Direction.NORTH, part, SemanticRole.FILL, null, Set.of());
    }

    /**
     * 带角色
     */
    public static SemanticPlacementOp of(BlockPos pos, SemanticPart part, SemanticRole role) {
        return new SemanticPlacementOp(pos, Direction.NORTH, part, role, null, Set.of());
    }

    /**
     * 带角色和标签
     */
    public static SemanticPlacementOp of(BlockPos pos, SemanticPart part, SemanticRole role, Set<String> tags) {
        return new SemanticPlacementOp(pos, Direction.NORTH, part, role, null, tags == null ? Set.of() : tags);
    }

    /**
     * 带朝向
     */
    public static SemanticPlacementOp of(BlockPos pos, Direction facing, SemanticPart part) {
        return new SemanticPlacementOp(pos, facing, part, SemanticRole.FILL, null, Set.of());
    }

    /**
     * 带朝向和角色
     */
    public static SemanticPlacementOp of(BlockPos pos, Direction facing, SemanticPart part, SemanticRole role) {
        return new SemanticPlacementOp(pos, facing, part, role, null, Set.of());
    }

    /**
     * 带朝向、角色和几何意图
     */
    public static SemanticPlacementOp of(BlockPos pos, Direction facing, SemanticPart part, SemanticRole role, GeometryIntent geometry) {
        return new SemanticPlacementOp(pos, facing, part, role, geometry, Set.of());
    }

    /**
     * 完整构造
     */
    public static SemanticPlacementOp of(BlockPos pos, Direction facing, SemanticPart part, SemanticRole role, GeometryIntent geometry, Set<String> tags) {
        return new SemanticPlacementOp(pos, facing, part, role, geometry, tags == null ? Set.of() : tags);
    }

    /**
     * 创建副本但修改几何意图
     */
    public SemanticPlacementOp withGeometry(GeometryIntent geometry) {
        return new SemanticPlacementOp(pos, facing, part, role, geometry, tags);
    }

    /**
     * 创建副本但修改朝向
     */
    public SemanticPlacementOp withFacing(Direction facing) {
        return new SemanticPlacementOp(pos, facing, part, role, geometry, tags);
    }
}

