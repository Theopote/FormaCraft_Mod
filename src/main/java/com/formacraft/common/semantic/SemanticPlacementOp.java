package com.formacraft.common.semantic;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * 语义放置指令
 * 
 * Generator 不再输出 minecraft:block_id，而输出：
 * - 位置 pos
 * - 语义部位 part（墙体、路面、栏杆…）
 * - 语义角色 role（FILL / EDGE / PILLAR / TRIM…）
 * - 可选 tag（比如 "weathered"、"corner"、"gate"）
 */
public record SemanticPlacementOp(
        BlockPos pos,
        SemanticPart part,
        SemanticRole role,
        Set<String> tags
) {
    public static SemanticPlacementOp of(BlockPos pos, SemanticPart part) {
        return new SemanticPlacementOp(pos, part, SemanticRole.FILL, Set.of());
    }

    public static SemanticPlacementOp of(BlockPos pos, SemanticPart part, SemanticRole role) {
        return new SemanticPlacementOp(pos, part, role, Set.of());
    }

    public static SemanticPlacementOp of(BlockPos pos, SemanticPart part, SemanticRole role, Set<String> tags) {
        return new SemanticPlacementOp(pos, part, role, tags == null ? Set.of() : tags);
    }
}

