package com.formacraft.common.llm.bridge;

import com.formacraft.common.llm.dto.PatchBlock;
import com.formacraft.common.llm.dto.PatchBlockSection;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * BlockPatchBridge（BlockPatch 转换桥接器）
 * 
 * 核心功能：
 * - 将 LLM 输出的 PatchBlockSection 转换为现有的 BlockPatch 系统
 * - 处理 action 标准化（place/remove/replace）
 * - 处理 targetBlock 默认值
 */
public final class BlockPatchBridge {

    private BlockPatchBridge() {}

    /**
     * 将 PatchBlockSection 的 origin 转换为 BlockPos
     */
    public static BlockPos toOrigin(PatchBlockSection section) {
        if (section == null || section.origin() == null) return BlockPos.ORIGIN;
        return new BlockPos(section.origin().x(), section.origin().y(), section.origin().z());
    }

    /**
     * 将 PatchBlockSection 转换为 BlockPatch 列表
     */
    public static List<BlockPatch> toBlockPatches(PatchBlockSection section) {
        List<BlockPatch> out = new ArrayList<>();
        if (section == null || section.blocks() == null) return out;

        for (PatchBlock b : section.blocks()) {
            if (b == null) continue;
            String action = normalizeAction(b.action());
            String target = (action.equals(BlockPatch.REMOVE)) ? "minecraft:air" : safeTarget(b.targetBlock());
            out.add(new BlockPatch(action, b.dx(), b.dy(), b.dz(), target));
        }
        return out;
    }

    /**
     * 标准化 action（place/remove/replace）
     */
    private static String normalizeAction(String a) {
        if (a == null) return BlockPatch.PLACE;
        String v = a.trim().toLowerCase();
        return switch (v) {
            case "remove" -> BlockPatch.REMOVE;
            case "replace" -> BlockPatch.REPLACE;
            default -> BlockPatch.PLACE;
        };
    }

    /**
     * 安全的 targetBlock（提供默认值）
     */
    private static String safeTarget(String id) {
        if (id == null || id.isBlank()) return "minecraft:stone";
        return id.trim();
    }
}

